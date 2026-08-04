package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationIdempotencyConcurrencyTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteByRoomTypeIdNotIn(List.of("DELUXE_A", "STANDARD_B"));
    }

    @Test
    void 같은_웹훅이_동시에_20번_재전송돼도_예약은_한_건만_반영된다() throws Exception {
        String roomTypeId = "DUP_" + UUID.randomUUID();
        String externalId = "DUP-EXT-" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 10));

        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger createdCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, requestCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    ReserveRequest request = new ReserveRequest("AIRBNB", externalId, roomTypeId, stayDate, false);
                    ResponseEntity<ReserveResponse> response =
                            restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        createdCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await();
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(reservationRepository.findByChannelAndExternalReservationId("AIRBNB", externalId)).isPresent();
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(roomInventoryRepository.findByRoomTypeIdAndStayDate(roomTypeId, stayDate).orElseThrow().getAvailableStock()).isEqualTo(9);
    }
}
