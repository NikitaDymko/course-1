package io.github.mitohondriyaa.notification.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Запись журнала уведомлений. Хранится в Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRecord {
    private String orderNumber;
    private String type;
    private String recipient;
    private String subject;
    private LocalDateTime sentAt;
}
