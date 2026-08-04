package com.handys.reservation.web.dto;

import java.time.LocalDate;

public record ReservationDetailResponse(
        Long id, String channel, String externalReservationId,
        String roomTypeId, LocalDate stayDate, String status
) {
}
