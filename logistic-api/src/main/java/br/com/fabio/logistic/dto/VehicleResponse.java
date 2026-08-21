package br.com.fabio.logistic.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String name,
        Integer capacityKg,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
