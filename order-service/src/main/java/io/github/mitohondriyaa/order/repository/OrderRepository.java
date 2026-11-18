package io.github.mitohondriyaa.order.repository;

import io.github.mitohondriyaa.order.model.Order;
import io.github.mitohondriyaa.order.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserId(String userId);
    Optional<Order> findByIdAndUserId(Long id, String userId);
    Optional<Order> findByOrderNumber(String orderNumber);
    List<Order> findByStatus(OrderStatus status);
}
