package br.com.fabio.logistic.dto;

import java.util.UUID;

/** Todos os campos são opcionais e combinados em AND. */
public record VehicleFilter(
        String name,
        Integer capacityMin,
        Integer capacityMax,
        UUID driverId) {
}
