package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;

public interface DownstreamClient {
    void call(OutboxEventType eventType, Long reservationId, boolean simulateFailure);
}
