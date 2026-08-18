package br.com.fabio.logistic.exception;

import java.time.LocalDateTime;
import java.util.Map;

/** Formato único de erro devolvido pela API. */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String message,
        Map<String, String> errors) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(LocalDateTime.now(), status, message, Map.of());
    }

    public static ErrorResponse of(int status, String message, Map<String, String> errors) {
        return new ErrorResponse(LocalDateTime.now(), status, message, errors);
    }
}
