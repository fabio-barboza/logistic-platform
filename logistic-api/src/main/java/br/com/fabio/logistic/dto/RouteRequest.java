package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.RouteStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RouteRequest(
        @NotNull(message = "motorista é obrigatório") UUID driverId,
        @NotNull(message = "status é obrigatório") RouteStatus status) {
}
