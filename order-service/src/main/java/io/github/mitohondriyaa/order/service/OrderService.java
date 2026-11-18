package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.order.client.InventoryClient;
import io.github.mitohondriyaa.order.client.ProductClient;
import io.github.mitohondriyaa.order.client.ProductInfo;
import io.github.mitohondriyaa.order.dto.CustomerRequest;
import io.github.mitohondriyaa.order.dto.OrderItemRequest;
import io.github.mitohondriyaa.order.dto.OrderItemResponse;
import io.github.mitohondriyaa.order.dto.OrderRequest;
import io.github.mitohondriyaa.order.dto.OrderResponse;
import io.github.mitohondriyaa.order.dto.UserDetailsResponse;
import io.github.mitohondriyaa.order.event.OrderCancelledEvent;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderItemEvent;
import io.github.mitohondriyaa.order.event.OrderPlacedEvent;
import io.github.mitohondriyaa.order.event.OrderStatusChangedEvent;
import io.github.mitohondriyaa.order.exception.InvalidOrderStateException;
import io.github.mitohondriyaa.order.exception.NotFoundException;
import io.github.mitohondriyaa.order.exception.OutOfStockException;
import io.github.mitohondriyaa.order.model.Customer;
import io.github.mitohondriyaa.order.model.Order;
import io.github.mitohondriyaa.order.model.OrderItem;
import io.github.mitohondriyaa.order.model.OrderStatus;
import io.github.mitohondriyaa.order.model.PaymentMethod;
import io.github.mitohondriyaa.order.model.Warranty;
import io.github.mitohondriyaa.order.repository.OrderRepository;
import io.github.mitohondriyaa.order.repository.WarrantyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Бизнес-логика заказов.
 *
 * Жизненный цикл: NEW → ACCEPTED → AWAITING_PAYMENT → PAID → READY → ISSUED,
 * а также REJECTED и CANCELLED. Все события пишутся в таблицу исходящих
 * сообщений в той же транзакции (шаблон transactional outbox).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private final OrderRepository orderRepository;
    private final WarrantyRepository warrantyRepository;
    private final InventoryClient inventoryClient;
    private final ProductClient productClient;
    private final PriceCalculator priceCalculator;
    private final AuditService auditService;
    private final OutboxService outboxService;

    /**
     * Оформление заказа.
     *
     * Расчет стоимости: сумма заказа S = Σ pi·qi, итоговая
     * стоимость C = S·(1 – d/100). Заказ сохраняется в статусе
     * NEW и публикуется событие order-placed; после подтверждения резерва
     * (inventory-reserved) заказ переходит в ACCEPTED, а затем в
     * AWAITING_PAYMENT — статус «Ожидает оплаты».
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest orderRequest, Jwt jwt) {
        BigDecimal discount = orderRequest.discount() == null
            ? BigDecimal.ZERO
            : orderRequest.discount();
        boolean managerApproved = Boolean.TRUE.equals(orderRequest.managerApproved());

        priceCalculator.validateDiscount(discount, managerApproved);

        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        order.setStatus(OrderStatus.NEW);
        order.setDiscount(discount);
        order.setUserId(jwt.getSubject());
        order.setCustomer(toCustomer(orderRequest.customer()));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : orderRequest.items()) {
            ProductInfo product = productClient.getProductById(itemRequest.productId());

            if (!inventoryClient.isInStock(itemRequest.productId(), itemRequest.quantity())) {
                throw new OutOfStockException(
                    "Out of stock for product %s".formatted(itemRequest.productId())
                );
            }

            OrderItem item = new OrderItem();
            item.setProductId(itemRequest.productId());
            item.setQuantity(itemRequest.quantity());
            item.setPrice(product.price());
            item.setWarrantyMonths(product.warrantyMonths() == null ? 0 : product.warrantyMonths());
            order.addItem(item);

            total = total.add(priceCalculator.calculateTotal(product.price(), itemRequest.quantity()));
        }

        order.setTotalAmount(total);
        order.setFinalAmount(priceCalculator.calculateFinalAmount(total, discount));

        orderRepository.save(order);

        saveWarranties(order);
        auditService.log(order.getId(), "ORDER_PLACED", jwt.getSubject(),
            "Order %s placed".formatted(order.getOrderNumber()));

        outboxService.save("order-placed", "OrderPlacedEvent", toPlacedEvent(order));

        return toResponse(order);
    }

    /**
     * Принятие заказа по подтверждению резерва (сага, событие inventory-reserved).
     *
     * Заказ перечитывается из репозитория: слушатель мог получить его
     * в устаревшем состоянии, а переход NEW → ACCEPTED должен выполняться
     * над актуальной версией.
     */
    @Transactional
    public void acceptBySaga(Long orderId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.NEW) {
            log.info("Order {} already in status {}, skip accept",
                order.getOrderNumber(), order.getStatus());
            return;
        }
        changeStatus(order, OrderStatus.ACCEPTED, "inventory-service");
    }

    /**
     * Отклонение заказа при нехватке товара (сага, событие inventory-rejected).
     */
    @Transactional
    public void rejectBySaga(Long orderId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.NEW) {
            log.info("Order {} already in status {}, skip reject",
                order.getOrderNumber(), order.getStatus());
            return;
        }
        changeStatus(order, OrderStatus.REJECTED, "inventory-service");
        outboxService.save("order-cancelled", "OrderCancelledEvent", toCancelledEvent(order));
    }

    /**
     * Принятие заказа в работу (NEW → ACCEPTED).
     */
    @Transactional
    public OrderResponse acceptOrder(Long id, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.NEW);
        changeStatus(order, OrderStatus.ACCEPTED, jwt.getSubject());
        return toResponse(order);
    }

    /**
     * Отклонение заказа (NEW → REJECTED) с возвратом резерва.
     */
    @Transactional
    public OrderResponse rejectOrder(Long id, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.NEW);
        changeStatus(order, OrderStatus.REJECTED, jwt.getSubject());
        outboxService.save("order-cancelled", "OrderCancelledEvent", toCancelledEvent(order));
        return toResponse(order);
    }

    /**
     * Переход к ожиданию оплаты (ACCEPTED → AWAITING_PAYMENT).
     */
    @Transactional
    public OrderResponse awaitPayment(Long id, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.ACCEPTED);
        changeStatus(order, OrderStatus.AWAITING_PAYMENT, jwt.getSubject());
        return toResponse(order);
    }

    /**
     * Регистрация оплаты и формирование номера чека
     * (AWAITING_PAYMENT → PAID).
     */
    @Transactional
    public OrderResponse payOrder(Long id, PaymentMethod paymentMethod, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.AWAITING_PAYMENT);

        order.setPaymentMethod(paymentMethod);
        order.setReceiptNumber("RCPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setPaidAt(LocalDateTime.now());

        changeStatus(order, OrderStatus.PAID, jwt.getSubject());
        return toResponse(order);
    }

    /**
     * Готовность заказа к выдаче (PAID → READY).
     */
    @Transactional
    public OrderResponse markReady(Long id, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.PAID);
        changeStatus(order, OrderStatus.READY, jwt.getSubject());
        return toResponse(order);
    }

    /**
     * Выдача заказа (READY → ISSUED). Публикуется событие order-issued.
     */
    @Transactional
    public OrderResponse issueOrder(Long id, Jwt jwt) {
        Order order = findOrder(id);
        requireStatus(order, OrderStatus.READY);

        order.setIssuedAt(LocalDateTime.now());
        changeStatus(order, OrderStatus.ISSUED, jwt.getSubject());

        outboxService.save("order-issued", "OrderIssuedEvent", toIssuedEvent(order, jwt.getSubject()));
        return toResponse(order);
    }

    /**
     * Отмена заказа покупателем. Возврат резерва выполняется
     * inventory-service по событию order-cancelled.
     */
    @Transactional
    public void cancelOrder(Long id, String userId) {
        Order order = orderRepository.findByIdAndUserId(id, userId).orElseThrow(
            () -> new NotFoundException("Order with id %d not found".formatted(id))
        );

        if (order.getStatus() == OrderStatus.ISSUED) {
            throw new InvalidOrderStateException("Issued order cannot be cancelled");
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.log(order.getId(), "ORDER_CANCELLED", userId,
            "Order %s cancelled".formatted(order.getOrderNumber()));

        outboxService.save("order-cancelled", "OrderCancelledEvent", toCancelledEvent(order));
    }

    public List<OrderResponse> getOrdersByUserId(String userId) {
        return orderRepository.findByUserId(userId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public OrderResponse getOrderForUser(Long id, String userId) {
        Order order = orderRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NotFoundException("Order not found"));

        return toResponse(order);
    }

    /**
     * Отчет по заказам с заданным статусом.
     * Если статус не указан, возвращаются все заказы.
     */
    public List<OrderResponse> getOrdersByStatus(OrderStatus status) {
        List<Order> orders = status == null
            ? orderRepository.findAll()
            : orderRepository.findByStatus(status);

        return orders.stream()
            .map(this::toResponse)
            .toList();
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Order with id %d not found".formatted(id)));
    }

    private void requireStatus(Order order, OrderStatus expected) {
        if (order.getStatus() != expected) {
            throw new InvalidOrderStateException(
                "Order %s must be in status %s, but was %s"
                    .formatted(order.getOrderNumber(), expected, order.getStatus())
            );
        }
    }

    private void changeStatus(Order order, OrderStatus newStatus, String performedBy) {
        OrderStatus previous = order.getStatus();
        order.setStatus(newStatus);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.log(order.getId(), "STATUS_CHANGED", performedBy,
            "%s -> %s".formatted(previous, newStatus));

        outboxService.save("order-status-changed", "OrderStatusChangedEvent",
            toStatusChangedEvent(order, performedBy));
    }

    private void saveWarranties(Order order) {
        LocalDate start = LocalDate.now();
        for (OrderItem item : order.getItems()) {
            if (item.getWarrantyMonths() == null || item.getWarrantyMonths() <= 0) {
                continue;
            }

            Warranty warranty = new Warranty();
            warranty.setOrderId(order.getId());
            warranty.setProductId(item.getProductId());
            warranty.setWarrantyMonths(item.getWarrantyMonths());
            warranty.setStartsAt(start);
            warranty.setEndsAt(start.plusMonths(item.getWarrantyMonths()));
            warrantyRepository.save(warranty);
        }
    }

    private Customer toCustomer(CustomerRequest request) {
        Customer customer = new Customer();
        customer.setEmail(request.email());
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(request.phone());
        customer.setPersonalDataConsent(request.personalDataConsent());
        return customer;
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
            .map(item -> new OrderItemResponse(
                item.getProductId(),
                item.getQuantity(),
                item.getPrice(),
                item.getWarrantyMonths(),
                item.getLineTotal()
            ))
            .toList();

        Customer customer = order.getCustomer();

        return new OrderResponse(
            order.getId(),
            order.getOrderNumber(),
            order.getStatus(),
            items,
            order.getDiscount(),
            order.getTotalAmount(),
            order.getFinalAmount(),
            order.getPaymentMethod(),
            order.getReceiptNumber(),
            order.getPaidAt(),
            order.getIssuedAt(),
            order.getCreatedAt(),
            new UserDetailsResponse(
                customer.getEmail(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getPhone()
            )
        );
    }

    private List<OrderItemEvent> toItemEvents(Order order) {
        List<OrderItemEvent> events = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            events.add(new OrderItemEvent(item.getProductId(), item.getQuantity()));
        }
        return events;
    }

    private OrderPlacedEvent toPlacedEvent(Order order) {
        Customer customer = order.getCustomer();
        return new OrderPlacedEvent(
            order.getOrderNumber(),
            toItemEvents(order),
            customer.getEmail(),
            customer.getFirstName(),
            customer.getLastName()
        );
    }

    private OrderCancelledEvent toCancelledEvent(Order order) {
        Customer customer = order.getCustomer();
        return new OrderCancelledEvent(
            order.getOrderNumber(),
            toItemEvents(order),
            customer.getEmail(),
            customer.getFirstName(),
            customer.getLastName()
        );
    }

    private OrderStatusChangedEvent toStatusChangedEvent(Order order, String changedBy) {
        Customer customer = order.getCustomer();
        return new OrderStatusChangedEvent(
            order.getOrderNumber(),
            order.getStatus().name(),
            LocalDateTime.now().toString(),
            changedBy,
            customer.getEmail(),
            customer.getFirstName(),
            customer.getLastName()
        );
    }

    private OrderIssuedEvent toIssuedEvent(Order order, String issuedBy) {
        return new OrderIssuedEvent(
            order.getOrderNumber(),
            order.getIssuedAt().toString(),
            issuedBy,
            toItemEvents(order)
        );
    }
}
