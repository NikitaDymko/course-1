package io.github.mitohondriyaa.order.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Запись журнала действий (аудита). Фиксирует сотрудника или сервис,
 * инициировавший операцию.
 */
@Entity
@Table(name = "t_audit_log")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class AuditLogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false)
    private String action;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @Column(length = 1000)
    private String details;
}
