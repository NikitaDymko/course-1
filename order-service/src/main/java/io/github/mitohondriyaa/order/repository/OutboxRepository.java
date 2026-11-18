package io.github.mitohondriyaa.order.repository;

import io.github.mitohondriyaa.order.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByPublishedFalseOrderByIdAsc();
}
