package br.com.fabio.logistic.exception;

/** Lançada em conflitos de negócio: e-mail duplicado, vínculo repetido, etc. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
