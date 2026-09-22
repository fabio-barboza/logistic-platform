package br.com.fabio.logistic.dto;

import java.util.UUID;

public record VehicleFilter(
        String name,
        Integer capacityMinKg,
        Integer capacityMaxKg,
        UUID driverId) {
}
