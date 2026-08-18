package br.com.fabio.logistic.dto;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OrderRequest(
        UUID routeId,
        @NotBlank(message = "CEP é obrigatório") String zipCode,
        @NotBlank(message = "bairro é obrigatório") String neighborhood,
        @NotBlank(message = "cidade é obrigatória") String city,
        @NotBlank(message = "estado é obrigatório") @Size(min = 2, max = 2, message = "estado deve ter 2 caracteres") String state,
        @NotNull(message = "status é obrigatório") OrderStatus status) {
}
