package br.com.fabio.logisticagent.service;

/**
 * Intenção de escrita pendente, indexada por conversa (ver
 * {@link br.com.fabio.logisticagent.security.AuthenticatedUser#conversationId}).
 *
 * <p>O pedido de escrita e o turno que o aceita ("sim, pode cadastrar") são requisições
 * distintas, e é justamente no turno do aceite que o modelo dá a gravação por feita sem ter
 * chamado tool nenhuma.
 */
public interface IConversationStateStore {

    boolean hasWriteIntent(String conversationId);

    void setWriteIntent(String conversationId, boolean requested);
}
