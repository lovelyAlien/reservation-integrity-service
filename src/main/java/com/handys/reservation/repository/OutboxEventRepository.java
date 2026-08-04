package com.handys.reservation.repository;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusAndNextRetryAtLessThanEqual(OutboxStatus status, LocalDateTime now);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    List<OutboxEvent> findByReservationId(Long reservationId);
}
