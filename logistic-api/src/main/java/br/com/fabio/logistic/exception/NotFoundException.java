package br.com.fabio.logistic.exception;

/** Lançada quando um recurso buscado por id não existe. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
