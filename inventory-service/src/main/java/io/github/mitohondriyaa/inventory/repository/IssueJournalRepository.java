package io.github.mitohondriyaa.inventory.repository;

import io.github.mitohondriyaa.inventory.model.IssueJournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueJournalRepository extends JpaRepository<IssueJournalEntry, Long> {
    List<IssueJournalEntry> findByOrderNumber(String orderNumber);
}
