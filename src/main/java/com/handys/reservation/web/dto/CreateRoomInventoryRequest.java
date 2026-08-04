package com.handys.reservation.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateRoomInventoryRequest(
        @NotBlank String roomTypeId,
        @NotNull LocalDate stayDate,
        @Min(0) int totalStock
) {
}
