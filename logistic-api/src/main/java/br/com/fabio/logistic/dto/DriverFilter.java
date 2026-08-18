package br.com.fabio.logistic.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Todos os campos são opcionais e combinados em AND. */
public record DriverFilter(
        String name,
        String email,
        String city,
        String state,
        LocalDate birthdayFrom,
        LocalDate birthdayTo,
        UUID vehicleId) {
}
