package br.com.fabio.logistic.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record DriverResponse(
        UUID id,
        String name,
        String email,
        LocalDate birthday,
        String city,
        String state,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
