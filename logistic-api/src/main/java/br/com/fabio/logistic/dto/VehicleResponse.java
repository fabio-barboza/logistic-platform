package br.com.fabio.logistic.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String name,
        Integer capacity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
