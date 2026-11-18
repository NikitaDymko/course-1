package io.github.mitohondriyaa.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Идемпотентность обработки сообщений на уровне БД.
 * Повторная обработка сообщения с тем же messageId игнорируется.
 */
@Entity
@Table(name = "t_processed_messages")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ProcessedMessage {
    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
