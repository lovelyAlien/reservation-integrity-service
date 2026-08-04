package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.downstream.DownstreamClient;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxDispatcher {

    private final OutboxEventRepository outboxEventRepository;
    private final ReservationRepository reservationRepository;
    private final DownstreamClient downstreamClient;

    public OutboxDispatcher(OutboxEventRepository outboxEventRepository,
                             ReservationRepository reservationRepository,
                             DownstreamClient downstreamClient) {
        this.outboxEventRepository = outboxEventRepository;
        this.reservationRepository = reservationRepository;
        this.downstreamClient = downstreamClient;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-fixed-delay-ms:3000}")
    public void dispatchPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByStatusAndNextRetryAtLessThanEqual(OutboxStatus.PENDING, LocalDateTime.now());
        for (OutboxEvent event : pending) {
            dispatchOne(event.getId());
        }
    }

    @Transactional
    public void dispatchOne(Long outboxEventId) {
        OutboxEvent event = outboxEventRepository.findById(outboxEventId).orElseThrow();
        boolean simulateFailure = reservationRepository.findById(event.getReservationId())
                .map(Reservation::isSimulateDownstreamFailure)
                .orElse(false);
        try {
            downstreamClient.call(event.getEventType(), event.getReservationId(), simulateFailure);
            event.markSuccess();
        } catch (RuntimeException e) {
            event.markFailure(e.getMessage());
        }
    }
}
