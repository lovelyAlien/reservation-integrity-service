package com.handys.reservation.exception;

import java.time.LocalDate;

public class SoldOutException extends RuntimeException {
    public SoldOutException(String roomTypeId, LocalDate stayDate) {
        super("no stock left: roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
    }
}
