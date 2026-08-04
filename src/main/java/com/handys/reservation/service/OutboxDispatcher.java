package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventProcessor outboxEventProcessor;

    public OutboxDispatcher(OutboxEventRepository outboxEventRepository,
                             OutboxEventProcessor outboxEventProcessor) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventProcessor = outboxEventProcessor;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-fixed-delay-ms:3000}")
    public void dispatchPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByStatusAndNextRetryAtLessThanEqual(OutboxStatus.PENDING, LocalDateTime.now());
        log.debug("Outbox 폴링 실행: 처리 대상 {}건", pending.size());
        for (OutboxEvent event : pending) {
            dispatchOne(event.getId());
        }
    }

    public void dispatchOne(Long outboxEventId) {
        outboxEventProcessor.process(outboxEventId);
    }
}
