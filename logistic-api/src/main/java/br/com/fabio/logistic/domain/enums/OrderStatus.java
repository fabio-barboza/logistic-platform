package br.com.fabio.logistic.domain.enums;

/** Status possíveis de um pedido. DELIVERED e DELIVER_FAILURE são finalizadores. */
public enum OrderStatus {
    DELIVERED,
    IN_ROUTE,
    COLLECTED,
    CANCELED,
    DELIVER_FAILURE
}
