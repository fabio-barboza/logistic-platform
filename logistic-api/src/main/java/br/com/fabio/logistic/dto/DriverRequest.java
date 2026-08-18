package br.com.fabio.logistic.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record DriverRequest(
        @NotBlank(message = "nome é obrigatório") String name,
        @NotBlank(message = "e-mail é obrigatório") @Email(message = "deve ser um e-mail válido") String email,
        @Past(message = "data de nascimento deve estar no passado") LocalDate birthday,
        @NotBlank(message = "cidade é obrigatória") String city,
        @NotBlank(message = "estado é obrigatório") @Size(min = 2, max = 2, message = "estado deve ter 2 caracteres") String state) {
}
