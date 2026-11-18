package io.github.mitohondriyaa.order.repository;

import io.github.mitohondriyaa.order.model.Warranty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarrantyRepository extends JpaRepository<Warranty, Long> {
    List<Warranty> findByOrderId(Long orderId);
}
