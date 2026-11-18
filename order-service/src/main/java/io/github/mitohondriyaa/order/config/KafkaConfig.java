package io.github.mitohondriyaa.order.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
@Profile("!test")
public class KafkaConfig {
    /**
     * Обработчик ошибок потребителя.
     *
     * Битое (не-Avro) сообщение нельзя разобрать, поэтому повторять его
     * бессмысленно: обработчик пишет запись в лог и сдвигает позицию
     * потребителя за неё, иначе партиция блокируется навсегда и все
     * следующие сообщения не доходят до слушателя.
     *
     * Остальные ошибки повторяются с паузой.
     */
    @Bean
    public DefaultErrorHandler errorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
            (record, exception) -> log.error(
                "Kafka error. Topic: {}, Partition: {}, Offset: {}. Record skipped.",
                record.topic(), record.partition(), record.offset(), exception
            ),
            new FixedBackOff(1000, 3)
        );
        handler.addNotRetryableExceptions(RecordDeserializationException.class);

        return handler;
    }

    /**
     * Фабрика контейнеров слушателей с нашим обработчиком ошибок.
     * Без явной привязки DefaultErrorHandler к фабрике ошибка десериализации
     * останавливает контейнер и партиция блокируется навсегда.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> consumerFactory,
        DefaultErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory
            = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    @Bean
    public NewTopic orderPlacedTopic() {
        return new NewTopic("order-placed", 3, (short) 2);
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return new NewTopic("order-cancelled", 3, (short) 2);
    }

    @Bean
    public NewTopic orderStatusChangedTopic() {
        return new NewTopic("order-status-changed", 3, (short) 2);
    }

    @Bean
    public NewTopic orderIssuedTopic() {
        return new NewTopic("order-issued", 3, (short) 2);
    }
}
