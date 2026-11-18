package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.order.model.AuditLogEntry;
import io.github.mitohondriyaa.order.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Журнал действий: фиксирует сотрудника или сервис, инициировавший операцию.
 */
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditLogRepository;

    public void log(Long orderId, String action, String performedBy, String details) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setOrderId(orderId);
        entry.setAction(action);
        entry.setPerformedBy(performedBy);
        entry.setPerformedAt(LocalDateTime.now());
        entry.setDetails(details);

        auditLogRepository.save(entry);
    }

    public List<AuditLogEntry> getAll() {
        return auditLogRepository.findAllByOrderByPerformedAtDesc();
    }

    public List<AuditLogEntry> getByOrder(Long orderId) {
        return auditLogRepository.findByOrderIdOrderByPerformedAtDesc(orderId);
    }
}
