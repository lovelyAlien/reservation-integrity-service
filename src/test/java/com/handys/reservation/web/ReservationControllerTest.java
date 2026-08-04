package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.ErrorResponse;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
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
class ReservationControllerTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    LocalDate stayDate = LocalDate.of(2030, 1, 1);

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteByRoomTypeIdNotIn(List.of("DELUXE_A", "STANDARD_B"));
    }

    private String seedInventory(int stock) {
        String roomTypeId = "TEST_" + UUID.randomUUID();
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, stock));
        return roomTypeId;
    }

    @Test
    void 신규_예약은_201과_CONFIRMED를_반환한다() {
        String roomTypeId = seedInventory(1);
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);

        ResponseEntity<ReserveResponse> response = restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void 같은_웹훅을_다시_보내면_200과_ALREADY_PROCESSED를_반환한다() {
        String roomTypeId = seedInventory(2);
        String externalId = "EXT-" + UUID.randomUUID();
        ReserveRequest request = new ReserveRequest("AIRBNB", externalId, roomTypeId, stayDate, false);
        restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        ResponseEntity<ReserveResponse> response = restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ALREADY_PROCESSED");
    }

    @Test
    void 재고가_없으면_409_SOLD_OUT을_반환한다() {
        String roomTypeId = seedInventory(0);
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/reservations", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().errorCode()).isEqualTo("SOLD_OUT");
    }

    @Test
    void 존재하지_않는_객실타입이면_404_ROOM_NOT_FOUND를_반환한다() {
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), "NO_SUCH_ROOM", stayDate, false);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/reservations", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("ROOM_NOT_FOUND");
    }
}
