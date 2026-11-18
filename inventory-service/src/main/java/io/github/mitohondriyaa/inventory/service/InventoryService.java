package io.github.mitohondriyaa.inventory.service;

import io.github.mitohondriyaa.inventory.dto.InventoryRequest;
import io.github.mitohondriyaa.inventory.dto.InventoryResponse;
import io.github.mitohondriyaa.inventory.event.InventoryRejectedEvent;
import io.github.mitohondriyaa.inventory.event.InventoryReservedEvent;
import io.github.mitohondriyaa.inventory.exception.NotFoundException;
import io.github.mitohondriyaa.inventory.model.Inventory;
import io.github.mitohondriyaa.inventory.model.IssueJournalEntry;
import io.github.mitohondriyaa.inventory.model.ProcessedMessage;
import io.github.mitohondriyaa.inventory.model.Reservation;
import io.github.mitohondriyaa.inventory.model.ReservationStatus;
import io.github.mitohondriyaa.inventory.repository.InventoryRepository;
import io.github.mitohondriyaa.inventory.repository.IssueJournalRepository;
import io.github.mitohondriyaa.inventory.repository.ProcessedMessageRepository;
import io.github.mitohondriyaa.inventory.repository.ReservationRepository;
import io.github.mitohondriyaa.order.event.OrderCancelledEvent;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderItemEvent;
import io.github.mitohondriyaa.order.event.OrderPlacedEvent;
import io.github.mitohondriyaa.product.event.ProductCreatedEvent;
import io.github.mitohondriyaa.product.event.ProductDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Склад: резервирование товара под заказ по принципу «всё или ничего»,
 * возврат резерва при отмене и журнал выдачи.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final IssueJournalRepository issueJournalRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisService redisService;

    @KafkaListener(topics = "product-created")
    public void createInventory(ProductCreatedEvent productCreatedEvent) {
        Inventory inventory = Inventory.builder()
            .productId(productCreatedEvent.getProductId().toString())
            .quantity(0)
            .build();

        inventoryRepository.save(inventory);
    }

    /**
     * Резервирование всех позиций заказа. Если хотя бы одной позиции
     * не хватает, резерв не создаётся ни по одной позиции и публикуется
     * событие inventory-rejected.
     */
    @KafkaListener(topics = "order-placed")
    @Transactional
    public void reserveStock(
        @Payload OrderPlacedEvent orderPlacedEvent,
        @Header(value = "messageId", required = false) String messageId
    ) {
        if (!markProcessed(messageId)) {
            return;
        }

        List<OrderItemEvent> items = orderPlacedEvent.getItems();

        boolean allAvailable = items.stream().allMatch(item ->
            inventoryRepository.existsByProductIdAndQuantityIsGreaterThanEqual(
                item.getProductId().toString(), item.getQuantity()
            )
        );

        if (!allAvailable) {
            publishRejected(orderPlacedEvent);
            return;
        }

        for (OrderItemEvent item : items) {
            inventoryRepository.decreaseQuantityIfEnough(item.getProductId().toString(), item.getQuantity());

            Reservation reservation = new Reservation();
            reservation.setOrderNumber(orderPlacedEvent.getOrderNumber().toString());
            reservation.setProductId(item.getProductId().toString());
            reservation.setQuantity(item.getQuantity());
            reservation.setStatus(ReservationStatus.RESERVED);
            reservation.setCreatedAt(LocalDateTime.now());
            reservationRepository.save(reservation);
        }

        publishReserved(orderPlacedEvent);
    }

    /**
     * Возврат резерва при отмене заказа.
     */
    @KafkaListener(topics = "order-cancelled")
    @Transactional
    public void orderCancelled(
        @Payload OrderCancelledEvent orderCancelledEvent,
        @Header(value = "messageId", required = false) String messageId
    ) {
        if (!markProcessed(messageId)) {
            return;
        }

        List<Reservation> reservations = reservationRepository.findByOrderNumberAndStatus(
            orderCancelledEvent.getOrderNumber().toString(), ReservationStatus.RESERVED
        );

        for (Reservation reservation : reservations) {
            inventoryRepository.increaseQuantityByProductId(
                reservation.getProductId(), reservation.getQuantity()
            );
            reservation.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(reservation);
        }
    }

    /**
     * Журнал выдачи: при выдаче заказа резервы помечаются как выданные
     * и фиксируются в журнале выдачи.
     */
    @KafkaListener(topics = "order-issued")
    @Transactional
    public void orderIssued(
        @Payload OrderIssuedEvent orderIssuedEvent,
        @Header(value = "messageId", required = false) String messageId
    ) {
        if (!markProcessed(messageId)) {
            return;
        }

        List<Reservation> reservations = reservationRepository.findByOrderNumberAndStatus(
            orderIssuedEvent.getOrderNumber().toString(), ReservationStatus.RESERVED
        );

        for (Reservation reservation : reservations) {
            reservation.setStatus(ReservationStatus.ISSUED);
            reservationRepository.save(reservation);

            IssueJournalEntry entry = new IssueJournalEntry();
            entry.setOrderNumber(orderIssuedEvent.getOrderNumber().toString());
            entry.setProductId(reservation.getProductId());
            entry.setQuantity(reservation.getQuantity());
            entry.setIssuedBy(orderIssuedEvent.getIssuedBy().toString());
            entry.setIssuedAt(LocalDateTime.now());
            issueJournalRepository.save(entry);
        }
    }

    public List<InventoryResponse> getAllInventories() {
        return inventoryRepository.findAll()
            .stream()
            .map(inventory -> new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getQuantity()))
            .toList();
    }

    public boolean isInStock(String productId, Integer quantity) {
        return inventoryRepository.existsByProductIdAndQuantityIsGreaterThanEqual(productId, quantity);
    }

    public InventoryResponse getInventoryByProductID(String productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> new NotFoundException("Inventory not found"));

        return new InventoryResponse(
            inventory.getId(),
            inventory.getProductId(),
            inventory.getQuantity()
        );
    }

    public InventoryResponse updateInventoryByProductId(InventoryRequest inventoryRequest) {
        Inventory inventory = inventoryRepository.findByProductId(inventoryRequest.productId())
            .orElseThrow(() -> new NotFoundException("Inventory not found"));

        inventory.setProductId(inventoryRequest.productId());
        inventory.setQuantity(inventoryRequest.quantity());

        inventoryRepository.save(inventory);

        return new InventoryResponse(
            inventory.getId(),
            inventory.getProductId(),
            inventory.getQuantity()
        );
    }

    @KafkaListener(topics = "product-deleted")
    public void deleteInventoryByProductID(ProductDeletedEvent productDeletedEvent) {
        inventoryRepository.deleteByProductId(productDeletedEvent.getProductId().toString());
    }

    /**
     * Идемпотентность: сообщение с уже обработанным messageId игнорируется.
     * Возвращает true, если сообщение новое и его нужно обработать.
     */
    private boolean markProcessed(String messageId) {
        if (messageId == null) {
            return true;
        }

        if (processedMessageRepository.existsById(messageId)) {
            log.info("Message {} already processed, skipping", messageId);
            return false;
        }

        processedMessageRepository.save(new ProcessedMessage(messageId, LocalDateTime.now()));
        return true;
    }

    private void publishReserved(OrderPlacedEvent orderPlacedEvent) {
        InventoryReservedEvent event = new InventoryReservedEvent();
        event.setOrderNumber(orderPlacedEvent.getOrderNumber().toString());
        event.setEmail(orderPlacedEvent.getEmail().toString());
        event.setFirstName(orderPlacedEvent.getFirstName().toString());
        event.setLastName(orderPlacedEvent.getLastName().toString());

        send("inventory-reserved", event);
    }

    private void publishRejected(OrderPlacedEvent orderPlacedEvent) {
        InventoryRejectedEvent event = new InventoryRejectedEvent();
        event.setOrderNumber(orderPlacedEvent.getOrderNumber().toString());
        event.setEmail(orderPlacedEvent.getEmail().toString());
        event.setFirstName(orderPlacedEvent.getFirstName().toString());
        event.setLastName(orderPlacedEvent.getLastName().toString());

        send("inventory-rejected", event);
    }

    private void send(String topic, Object event) {
        ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topic, event);
        producerRecord.headers().add("messageId", UUID.randomUUID().toString().getBytes());
        kafkaTemplate.send(producerRecord);
    }
}
