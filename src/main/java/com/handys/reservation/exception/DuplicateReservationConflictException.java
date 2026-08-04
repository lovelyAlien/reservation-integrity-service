package com.handys.reservation.exception;

public class DuplicateReservationConflictException extends RuntimeException {
    public DuplicateReservationConflictException(String channel, String externalReservationId) {
        super("duplicate concurrent reservation: channel=" + channel
                + ", externalReservationId=" + externalReservationId);
    }
}
