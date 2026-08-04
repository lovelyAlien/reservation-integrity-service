package com.handys.reservation.web;

import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.web.dto.OutboxEventResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OutboxEventQueryController {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventQueryController(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @GetMapping("/api/outbox-events")
    public List<OutboxEventResponse> findByStatus(@RequestParam OutboxStatus status) {
        return outboxEventRepository.findByStatus(status).stream()
                .map(e -> new OutboxEventResponse(e.getId(), e.getReservationId(), e.getEventType().name(),
                        e.getStatus().name(), e.getRetryCount(), e.getLastError()))
                .toList();
    }
}
