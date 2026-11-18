package io.github.mitohondriyaa.notification.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Задание на звонок клиенту. Создаётся при переходе заказа в статус ACCEPTED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallTask {
    private String orderNumber;
    private String phone;
    private String firstName;
    private String lastName;
    private String reason;
    private LocalDateTime createdAt;
    private boolean done;
}
