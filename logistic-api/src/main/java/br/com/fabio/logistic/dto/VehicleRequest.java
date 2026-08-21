package br.com.fabio.logistic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record VehicleRequest(
        @NotBlank(message = "nome é obrigatório") String name,
        @NotNull(message = "capacidade é obrigatória") @Positive(message = "capacidade deve ser positiva") Integer capacityKg) {
}
