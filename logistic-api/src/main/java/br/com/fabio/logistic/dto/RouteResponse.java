package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.RouteStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record RouteResponse(
        UUID id,
        UUID driverId,
        String driverName,
        RouteStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
