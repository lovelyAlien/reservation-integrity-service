package com.handys.reservation.web.dto;

public record OutboxEventResponse(
        Long id, Long reservationId, String eventType, String status, int retryCount, String lastError
) {
}
