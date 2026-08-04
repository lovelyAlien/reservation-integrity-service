package com.handys.reservation.web.dto;

import java.time.LocalDate;

public record RoomInventoryResponse(
        Long id, String roomTypeId, LocalDate stayDate, int totalStock, int availableStock
) {
}
