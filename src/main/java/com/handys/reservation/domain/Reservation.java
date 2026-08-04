package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String channel;

    @Column(name = "external_reservation_id", nullable = false, length = 100)
    private String externalReservationId;

    @Column(name = "room_type_id", nullable = false, length = 50)
    private String roomTypeId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "simulate_downstream_failure", nullable = false)
    private boolean simulateDownstreamFailure;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Reservation() {
    }

    public Reservation(String channel, String externalReservationId, String roomTypeId,
                        LocalDate stayDate, boolean simulateDownstreamFailure) {
        this.channel = channel;
        this.externalReservationId = externalReservationId;
        this.roomTypeId = roomTypeId;
        this.stayDate = stayDate;
        this.status = ReservationStatus.CONFIRMED;
        this.simulateDownstreamFailure = simulateDownstreamFailure;
    }

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public String getExternalReservationId() { return externalReservationId; }
    public String getRoomTypeId() { return roomTypeId; }
    public LocalDate getStayDate() { return stayDate; }
    public ReservationStatus getStatus() { return status; }
    public boolean isSimulateDownstreamFailure() { return simulateDownstreamFailure; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
