package br.com.fabio.logistic.domain.enums;

/** Status possíveis de uma rota. COMPLETED e COMPLETED_WITH_FAILURES são finalizadores. */
public enum RouteStatus {
    COMPLETED,
    COMPLETED_WITH_FAILURES,
    CANCELED,
    IN_PROGRESS
}
