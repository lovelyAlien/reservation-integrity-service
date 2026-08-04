package com.handys.reservation.web;

import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.service.ReservationCommand;
import com.handys.reservation.service.ReservationOutcome;
import com.handys.reservation.service.ReservationResult;
import com.handys.reservation.service.ReservationService;
import com.handys.reservation.web.dto.ReservationDetailResponse;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;

    public ReservationController(ReservationService reservationService,
                                  ReservationRepository reservationRepository) {
        this.reservationService = reservationService;
        this.reservationRepository = reservationRepository;
    }

    @PostMapping
    public ResponseEntity<ReserveResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationCommand command = new ReservationCommand(
                request.channel(), request.externalReservationId(), request.roomTypeId(),
                request.stayDate(), request.simulateDownstreamFailure());
        ReservationResult result = reservationService.reserve(command);
        HttpStatus status = result.outcome() == ReservationOutcome.CONFIRMED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ReserveResponse(result.reservationId(), result.outcome().name()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationDetailResponse> findById(@PathVariable Long id) {
        return reservationRepository.findById(id)
                .map(r -> ResponseEntity.ok(new ReservationDetailResponse(
                        r.getId(), r.getChannel(), r.getExternalReservationId(),
                        r.getRoomTypeId(), r.getStayDate(), r.getStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
