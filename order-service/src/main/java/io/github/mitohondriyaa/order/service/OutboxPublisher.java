package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.order.event.OrderCancelledEvent;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderPlacedEvent;
import io.github.mitohondriyaa.order.event.OrderStatusChangedEvent;
import io.github.mitohondriyaa.order.model.OutboxEvent;
import io.github.mitohondriyaa.order.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Отдельный публикатор: читает неопубликованные записи outbox и передает их
 * в Kafka. Доставка выполняется не менее одного раза, поэтому потребители
 * должны быть идемпотентными.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelayString = "${outbox.publisher.delay:2000}")
    public void publishPending() {
        List<OutboxEvent> events = outboxRepository.findTop100ByPublishedFalseOrderByIdAsc();

        for (OutboxEvent event : events) {
            try {
                SpecificRecord payload = fromJson(event.getEventType(), event.getPayload());

                ProducerRecord<String, Object> record
                    = new ProducerRecord<>(event.getTopic(), payload);
                record.headers().add("messageId", event.getMessageId().getBytes());

                kafkaTemplate.send(record).get();

                event.setPublished(true);
                event.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(event);
            } catch (Exception e) {
                log.warn("Cannot publish outbox event {}: {}", event.getId(), e.getMessage());
                return;
            }
        }
    }

    private SpecificRecord fromJson(String eventType, String payload) throws Exception {
        SpecificRecord template = switch (eventType) {
            case "OrderPlacedEvent" -> new OrderPlacedEvent();
            case "OrderCancelledEvent" -> new OrderCancelledEvent();
            case "OrderStatusChangedEvent" -> new OrderStatusChangedEvent();
            case "OrderIssuedEvent" -> new OrderIssuedEvent();
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };

        JsonDecoder decoder = DecoderFactory.get().jsonDecoder(template.getSchema(), payload);
        return new SpecificDatumReader<SpecificRecord>(template.getSchema()).read(null, decoder);
    }
}
