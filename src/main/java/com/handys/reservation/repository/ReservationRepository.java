package com.handys.reservation.repository;

import com.handys.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByChannelAndExternalReservationId(String channel, String externalReservationId);

    long countByRoomTypeId(String roomTypeId);
}
