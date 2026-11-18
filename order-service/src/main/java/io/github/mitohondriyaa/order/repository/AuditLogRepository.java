package io.github.mitohondriyaa.order.repository;

import io.github.mitohondriyaa.order.model.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {
    List<AuditLogEntry> findAllByOrderByPerformedAtDesc();
    List<AuditLogEntry> findByOrderIdOrderByPerformedAtDesc(Long orderId);
}
