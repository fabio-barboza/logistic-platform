package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderFilter(
        List<OrderStatus> status,
        UUID routeId,
        String city,
        String state,
        String neighborhood,
        String zipCode,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        Boolean unassigned) {
}
