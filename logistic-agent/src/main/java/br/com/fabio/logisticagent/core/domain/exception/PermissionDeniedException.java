package br.com.fabio.logisticagent.core.domain.exception;

public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(Throwable cause) {
        super(cause);
    }
}
