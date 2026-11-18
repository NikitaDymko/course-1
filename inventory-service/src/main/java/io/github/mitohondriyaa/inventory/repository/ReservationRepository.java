package io.github.mitohondriyaa.inventory.repository;

import io.github.mitohondriyaa.inventory.model.Reservation;
import io.github.mitohondriyaa.inventory.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByOrderNumber(String orderNumber);
    List<Reservation> findByOrderNumberAndStatus(String orderNumber, ReservationStatus status);
}
