package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.RouteStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RouteFilter(
        List<RouteStatus> status,
        UUID driverId,
        String driverName,
        LocalDateTime createdFrom,
        LocalDateTime createdTo) {
}
