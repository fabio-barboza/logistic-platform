package br.com.fabio.logistic.domain.enums;

public enum RouteStatus {
    COMPLETED("Concluído"),
    COMPLETED_WITH_FAILURES("Concluído com falhas"),
    CANCELED("Cancelado"),
    IN_PROGRESS("Em andamento");

    private final String description;

    RouteStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return this == COMPLETED || this == COMPLETED_WITH_FAILURES;
    }
}
