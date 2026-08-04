package com.handys.reservation.repository;

import com.handys.reservation.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RepositorySmokeTest {

    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 세_엔티티_모두_저장하고_조회할_수_있다() {
        String roomTypeId = "SMOKE_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);

        RoomInventory inventory = roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 3));
        boolean found = transactionTemplate.execute(status ->
                roomInventoryRepository.findForUpdate(roomTypeId, stayDate).isPresent());
        assertThat(found).isTrue();

        Reservation reservation = reservationRepository.save(
                new Reservation("AIRBNB", "SMOKE-EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false));
        assertThat(reservationRepository.findByChannelAndExternalReservationId(
                reservation.getChannel(), reservation.getExternalReservationId())).isPresent();

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(reservation.getId(), OutboxEventType.DOOR_LOCK_ISSUE));
        assertThat(outboxEventRepository.findByReservationId(reservation.getId())).containsExactly(event);

        assertThat(inventory.getId()).isNotNull();
    }
}
