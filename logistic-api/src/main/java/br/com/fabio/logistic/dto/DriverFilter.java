package br.com.fabio.logistic.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DriverFilter(
        String name,
        String email,
        String city,
        String state,
        LocalDate birthdayFrom,
        LocalDate birthdayTo,
        UUID vehicleId) {
}
