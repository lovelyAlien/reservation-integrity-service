package com.handys.reservation.service;

import java.time.LocalDate;

public record ReservationCommand(
        String channel,
        String externalReservationId,
        String roomTypeId,
        LocalDate stayDate,
        boolean simulateDownstreamFailure
) {
}
