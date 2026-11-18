package io.github.mitohondriyaa.inventory.repository;

import io.github.mitohondriyaa.inventory.model.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {
}
