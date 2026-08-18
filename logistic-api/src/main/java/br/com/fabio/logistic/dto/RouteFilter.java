package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.RouteStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Todos os campos são opcionais e combinados em AND. */
public record RouteFilter(
        List<RouteStatus> status,
        UUID driverId,
        String driverName,
        LocalDateTime createdFrom,
        LocalDateTime createdTo) {
}
