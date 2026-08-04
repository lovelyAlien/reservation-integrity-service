package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;

public class DownstreamCallFailedException extends RuntimeException {
    public DownstreamCallFailedException(OutboxEventType eventType, Long reservationId) {
        super("downstream call failed: eventType=" + eventType + ", reservationId=" + reservationId);
    }
}
