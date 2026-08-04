package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private OutboxEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry = 5;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt = LocalDateTime.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected OutboxEvent() {
    }

    public OutboxEvent(Long reservationId, OutboxEventType eventType) {
        this.reservationId = reservationId;
        this.eventType = eventType;
    }

    public boolean isExhausted() {
        return retryCount >= maxRetry;
    }

    public void markSuccess() {
        this.status = OutboxStatus.SUCCESS;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailure(String error) {
        this.retryCount += 1;
        this.lastError = error;
        this.updatedAt = LocalDateTime.now();
        if (isExhausted()) {
            this.status = OutboxStatus.FAILED;
        } else {
            long backoffSeconds = Math.min((long) Math.pow(2, retryCount), 60);
            this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        }
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public OutboxEventType getEventType() { return eventType; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
}
