package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.OutboxEventResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxDispatcherTest {

    @Autowired ReservationService reservationService;
    @Autowired OutboxDispatcher outboxDispatcher;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired TestRestTemplate restTemplate;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 다운스트림_호출이_계속_실패하면_최대_재시도_후_FAILED로_마감되고_조회_API로_추적된다() {
        String roomTypeId = "OUTBOX_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 1));

        ReservationCommand command = new ReservationCommand(
                "AIRBNB", "OUTBOX-EXT-" + UUID.randomUUID(), roomTypeId, stayDate, true);
        ReservationResult result = reservationService.reserve(command);

        List<OutboxEvent> events = outboxEventRepository.findByReservationId(result.reservationId());
        assertThat(events).hasSize(2);
        OutboxEvent target = events.get(0);

        for (int i = 0; i < target.getMaxRetry(); i++) {
            outboxDispatcher.dispatchOne(target.getId());
        }

        OutboxEvent updated = outboxEventRepository.findById(target.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(updated.getRetryCount()).isEqualTo(target.getMaxRetry());

        ResponseEntity<OutboxEventResponse[]> response =
                restTemplate.getForEntity("/api/outbox-events?status=FAILED", OutboxEventResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(OutboxEventResponse::id)
                .contains(target.getId());
    }
}
