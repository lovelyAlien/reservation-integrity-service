package com.handys.reservation.web;

import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoomNotFound(RoomNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ROOM_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(SoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SOLD_OUT", e.getMessage()));
    }

    @ExceptionHandler(DuplicateReservationConflictException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateConflict(DuplicateReservationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_CONFLICT", e.getMessage()));
    }
}
