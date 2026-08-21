package br.com.fabio.logistic.domain.enums;

/**
 * Status possíveis de um pedido. DELIVERED e DELIVER_FAILURE são finalizadores.
 *
 * <p>A descrição em PT-BR mora aqui para existir uma fonte só: o valor trafega em inglês (é o
 * enum, e é o que a LLM manda nos argumentos de tool e escreve no SQL), e a descrição é o que o
 * usuário lê. Quem exibe pergunta ao enum em vez de repetir a tradução.
 */
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

    /** Finalizador: não há transição posterior a partir deste status. */
    public boolean isFinal() {
        return this == DELIVERED || this == DELIVER_FAILURE;
    }
}
