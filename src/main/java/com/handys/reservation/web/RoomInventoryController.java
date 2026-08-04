package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.CreateRoomInventoryRequest;
import com.handys.reservation.web.dto.RoomInventoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/room-inventories")
public class RoomInventoryController {

    private final RoomInventoryRepository roomInventoryRepository;

    public RoomInventoryController(RoomInventoryRepository roomInventoryRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
    }

    @PostMapping
    public ResponseEntity<RoomInventoryResponse> create(@Valid @RequestBody CreateRoomInventoryRequest request) {
        RoomInventory saved = roomInventoryRepository.save(
                new RoomInventory(request.roomTypeId(), request.stayDate(), request.totalStock()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoomInventoryResponse(
                saved.getId(), saved.getRoomTypeId(), saved.getStayDate(),
                saved.getTotalStock(), saved.getAvailableStock()));
    }
}
