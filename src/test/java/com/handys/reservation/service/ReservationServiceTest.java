package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservationServiceTest {

    @Mock RoomInventoryRepository roomInventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock OutboxEventRepository outboxEventRepository;

    ReservationService reservationService;

    static final String CHANNEL = "AIRBNB";
    static final String EXTERNAL_ID = "AB-1";
    static final String ROOM_TYPE = "DELUXE_A";
    static final LocalDate STAY_DATE = LocalDate.of(2026, 8, 10);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reservationService = new ReservationService(roomInventoryRepository, reservationRepository, outboxEventRepository);
    }

    private ReservationCommand command() {
        return new ReservationCommand(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
    }

    private void setId(Reservation reservation, Long id) {
        try {
            var field = Reservation.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(reservation, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 재고행이_없으면_RoomNotFoundException() {
        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void 이미_처리된_웹훅이면_ALREADY_PROCESSED를_반환한다() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);
        Reservation existing = new Reservation(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
        setId(existing, 10L);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.of(existing));

        ReservationResult result = reservationService.reserve(command());

        assertThat(result.outcome()).isEqualTo(ReservationOutcome.ALREADY_PROCESSED);
        assertThat(result.reservationId()).isEqualTo(10L);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void 재고가_없으면_SoldOutException() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 0);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void 정상_예약이면_재고를_차감하고_outbox_2건을_생성한다() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);
        Reservation saved = new Reservation(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
        setId(saved, 20L);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenReturn(saved);

        ReservationResult result = reservationService.reserve(command());

        assertThat(result.outcome()).isEqualTo(ReservationOutcome.CONFIRMED);
        assertThat(result.reservationId()).isEqualTo(20L);
        assertThat(inventory.getAvailableStock()).isEqualTo(0);
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void insert시_유니크_제약_위반이면_DuplicateReservationConflictException() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());
        when(reservationRepository.saveAndFlush(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint \"uq_reservation_channel_external\""));

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(DuplicateReservationConflictException.class);
    }
}
