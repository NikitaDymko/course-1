package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.inventory.event.InventoryRejectedEvent;
import io.github.mitohondriyaa.inventory.event.InventoryReservedEvent;
import io.github.mitohondriyaa.order.model.Order;
import io.github.mitohondriyaa.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ответная часть саги оформления заказа.
 *
 * order-service публикует order-placed, inventory-service резервирует остатки
 * и отвечает событием inventory-reserved или inventory-rejected. Здесь заказ
 * переводится из NEW в ACCEPTED либо в REJECTED.
 *
 * Событие order-placed уходит в Kafka через outbox-публикатор, то есть уже
 * после коммита транзакции оформления, но ответ inventory-service может
 * прийти раньше, чем заказ станет виден читающей транзакции. Поэтому при
 * отсутствии заказа ответ не теряется, а обрабатывается повторно.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventsListener {
    /** Сколько раз пробуем найти заказ, прежде чем сдаться. */
    private static final int LOOKUP_ATTEMPTS = 10;
    /** Пауза между попытками, мс. */
    private static final long LOOKUP_PAUSE_MS = 300L;

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /**
     * Резерв подтвержден — заказ принимается в работу (NEW → ACCEPTED).
     */
    @KafkaListener(topics = "inventory-reserved")
    @Transactional
    public void onInventoryReserved(InventoryReservedEvent event) {
        String orderNumber = event.getOrderNumber().toString();
        log.info("Received inventory-reserved for order {}", orderNumber);

        findOrder(orderNumber, "inventory-reserved")
            .ifPresent(order -> orderService.acceptBySaga(order.getId()));
    }

    /**
     * Резерв отклонен (нехватка товара) — заказ отклоняется (NEW → REJECTED).
     */
    @KafkaListener(topics = "inventory-rejected")
    @Transactional
    public void onInventoryRejected(InventoryRejectedEvent event) {
        String orderNumber = event.getOrderNumber().toString();
        log.info("Received inventory-rejected for order {}", orderNumber);

        findOrder(orderNumber, "inventory-rejected")
            .ifPresent(order -> orderService.rejectBySaga(order.getId()));
    }

    /**
     * Ищет заказ по номеру, повторяя попытки: ответ саги может обогнать
     * коммит транзакции оформления заказа.
     */
    private Optional<Order> findOrder(String orderNumber, String topic) {
        for (int attempt = 1; attempt <= LOOKUP_ATTEMPTS; attempt++) {
            Optional<Order> order = orderRepository.findByOrderNumber(orderNumber);
            if (order.isPresent()) {
                return order;
            }

            log.info("Order {} not found on {} yet, attempt {}/{}",
                orderNumber, topic, attempt, LOOKUP_ATTEMPTS);

            try {
                Thread.sleep(LOOKUP_PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("Order {} not found on {} after {} attempts, skip event",
            orderNumber, topic, LOOKUP_ATTEMPTS);
        return Optional.empty();
    }
}
