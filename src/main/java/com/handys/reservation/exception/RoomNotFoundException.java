package com.handys.reservation.exception;

import java.time.LocalDate;

public class RoomNotFoundException extends RuntimeException {
    public RoomNotFoundException(String roomTypeId, LocalDate stayDate) {
        super("room inventory not found: roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
    }
}
