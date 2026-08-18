package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID routeId,
        String zipCode,
        String neighborhood,
        String city,
        String state,
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
