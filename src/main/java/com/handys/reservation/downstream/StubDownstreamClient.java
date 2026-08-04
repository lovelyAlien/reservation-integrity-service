package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StubDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(StubDownstreamClient.class);

    @Override
    public void call(OutboxEventType eventType, Long reservationId, boolean simulateFailure) {
        if (simulateFailure) {
            throw new DownstreamCallFailedException(eventType, reservationId);
        }
        log.info("다운스트림 처리 완료: eventType={}, reservationId={}", eventType, reservationId);
    }
}
