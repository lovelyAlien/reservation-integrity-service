package com.handys.reservation.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ReserveRequest(
        @NotBlank String channel,
        @NotBlank String externalReservationId,
        @NotBlank String roomTypeId,
        @NotNull LocalDate stayDate,
        boolean simulateDownstreamFailure
) {
}
