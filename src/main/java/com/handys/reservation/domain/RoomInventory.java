package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "room_inventory")
public class RoomInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_type_id", nullable = false, length = 50)
    private String roomTypeId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected RoomInventory() {
    }

    public RoomInventory(String roomTypeId, LocalDate stayDate, int totalStock) {
        this.roomTypeId = roomTypeId;
        this.stayDate = stayDate;
        this.totalStock = totalStock;
        this.availableStock = totalStock;
    }

    public boolean hasStock() {
        return availableStock > 0;
    }

    public void decrease() {
        if (!hasStock()) {
            throw new IllegalStateException(
                    "no stock left for roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
        }
        this.availableStock -= 1;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getRoomTypeId() { return roomTypeId; }
    public LocalDate getStayDate() { return stayDate; }
    public int getTotalStock() { return totalStock; }
    public int getAvailableStock() { return availableStock; }
}
