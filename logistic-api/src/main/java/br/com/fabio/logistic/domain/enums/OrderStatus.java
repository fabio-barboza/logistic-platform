package br.com.fabio.logistic.domain.enums;

public enum OrderStatus {
    DELIVERED("Entregue"),
    IN_ROUTE("Em rota"),
    COLLECTED("Coletado"),
    CANCELED("Cancelado"),
    DELIVER_FAILURE("Falha na entrega");

    private final String description;

    OrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return this == DELIVERED || this == DELIVER_FAILURE;
    }
}
