package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.order.model.OutboxEvent;
import io.github.mitohondriyaa.order.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.apache.avro.Schema;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Запись события в таблицу исходящих сообщений в той же транзакции,
 * что и бизнес-изменение (шаблон transactional outbox).
 * Событие хранится в виде Avro-JSON, чтобы публикатор мог восстановить
 * исходную Avro-запись и отправить её в Kafka.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {
    private final OutboxRepository outboxRepository;

    public void save(String topic, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setMessageId(UUID.randomUUID().toString());
        event.setTopic(topic);
        event.setEventType(eventType);
        event.setPayload(toJson(payload));
        event.setPublished(false);
        event.setCreatedAt(LocalDateTime.now());

        outboxRepository.save(event);
    }

    private String toJson(Object payload) {
        if (!(payload instanceof SpecificRecord record)) {
            throw new IllegalStateException("Outbox payload must be an Avro SpecificRecord");
        }

        try {
            Schema schema = record.getSchema();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, out);
            new SpecificDatumWriter<SpecificRecord>(schema).write(record, encoder);
            encoder.flush();

            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
    }
}
