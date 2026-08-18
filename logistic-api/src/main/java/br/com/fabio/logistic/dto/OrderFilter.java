package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Todos os campos são opcionais e combinados em AND. unassigned = true filtra route_id nulo. */
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
