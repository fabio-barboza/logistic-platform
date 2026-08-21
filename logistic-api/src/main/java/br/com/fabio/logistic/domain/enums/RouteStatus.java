package br.com.fabio.logistic.domain.enums;

/**
 * Status possíveis de uma rota. COMPLETED e COMPLETED_WITH_FAILURES são finalizadores.
 *
 * <p>A descrição em PT-BR mora aqui para existir uma fonte só: o valor trafega em inglês (é o
 * enum, e é o que a LLM manda nos argumentos de tool e escreve no SQL), e a descrição é o que o
 * usuário lê. Quem exibe pergunta ao enum em vez de repetir a tradução.
 */
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

    /** Finalizador: não há transição posterior a partir deste status. */
    public boolean isFinal() {
        return this == COMPLETED || this == COMPLETED_WITH_FAILURES;
    }
}
