package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.downstream.DownstreamClient;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final ReservationRepository reservationRepository;
    private final DownstreamClient downstreamClient;

    public OutboxEventProcessor(OutboxEventRepository outboxEventRepository,
                                 ReservationRepository reservationRepository,
                                 DownstreamClient downstreamClient) {
        this.outboxEventRepository = outboxEventRepository;
        this.reservationRepository = reservationRepository;
        this.downstreamClient = downstreamClient;
    }

    @Transactional
    public void process(Long outboxEventId) {
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
