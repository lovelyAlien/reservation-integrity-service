package com.handys.reservation.repository;

import com.handys.reservation.domain.RoomInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface RoomInventoryRepository extends JpaRepository<RoomInventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoomInventory r where r.roomTypeId = :roomTypeId and r.stayDate = :stayDate")
    Optional<RoomInventory> findForUpdate(@Param("roomTypeId") String roomTypeId,
                                           @Param("stayDate") LocalDate stayDate);
}
