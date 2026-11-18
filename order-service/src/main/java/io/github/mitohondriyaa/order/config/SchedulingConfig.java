package io.github.mitohondriyaa.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает планировщик (публикатор outbox). В тестах отключается
 * свойством outbox.scheduling.enabled=false, чтобы фоновые задачи
 * не обращались к БД после остановки контейнеров.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
