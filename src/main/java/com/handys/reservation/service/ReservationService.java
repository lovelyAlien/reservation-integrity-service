package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxEventType;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ReservationService {

    private final RoomInventoryRepository roomInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxEventRepository outboxEventRepository;

    public ReservationService(RoomInventoryRepository roomInventoryRepository,
                               ReservationRepository reservationRepository,
                               OutboxEventRepository outboxEventRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
        this.reservationRepository = reservationRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public ReservationResult reserve(ReservationCommand command) {
        RoomInventory inventory = roomInventoryRepository
                .findForUpdate(command.roomTypeId(), command.stayDate())
                .orElseThrow(() -> new RoomNotFoundException(command.roomTypeId(), command.stayDate()));

        Optional<Reservation> existing = reservationRepository.findByChannelAndExternalReservationId(
                command.channel(), command.externalReservationId());
        if (existing.isPresent()) {
            return new ReservationResult(existing.get().getId(), ReservationOutcome.ALREADY_PROCESSED);
        }

        if (!inventory.hasStock()) {
            throw new SoldOutException(command.roomTypeId(), command.stayDate());
        }
        inventory.decrease();

        Reservation reservation;
        try {
            reservation = reservationRepository.saveAndFlush(
                    new Reservation(command.channel(), command.externalReservationId(),
                            command.roomTypeId(), command.stayDate(), command.simulateDownstreamFailure()));
        } catch (DataIntegrityViolationException e) {
            String rootMessage = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            if (rootMessage != null && rootMessage.contains("uq_reservation_channel_external")) {
                throw new DuplicateReservationConflictException(command.channel(), command.externalReservationId());
            }
            throw e;
        }

        outboxEventRepository.save(new OutboxEvent(reservation.getId(), OutboxEventType.DOOR_LOCK_ISSUE));
        outboxEventRepository.save(new OutboxEvent(reservation.getId(), OutboxEventType.SETTLEMENT_TRIGGER));

        return new ReservationResult(reservation.getId(), ReservationOutcome.CONFIRMED);
    }
}
