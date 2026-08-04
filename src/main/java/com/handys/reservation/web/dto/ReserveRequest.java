package com.handys.reservation.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ReserveRequest(
        @NotBlank @Size(max = 30) String channel,
        @NotBlank @Size(max = 100) String externalReservationId,
        @NotBlank @Size(max = 50) String roomTypeId,
        @NotNull LocalDate stayDate,
        boolean simulateDownstreamFailure
) {
}
