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
class ReservationConcurrencyTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 마지막_재고_하나를_두고_50개가_동시에_경쟁해도_정확히_하나만_성공한다() throws Exception {
        String roomTypeId = "RACE_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 1));

        int requestCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, requestCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    ReserveRequest request = new ReserveRequest(
                            "AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);
                    ResponseEntity<ReserveResponse> response =
                            restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        soldOutCount.incrementAndGet();
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

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(soldOutCount.get()).isEqualTo(requestCount - 1);
    }
}
