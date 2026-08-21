package br.com.fabio.logisticagent.dto;

/**
 * Resposta do usuário à ação pendente. {@code approved=false} é o cancelamento — que também
 * consome a pendência: o botão de cancelar tem que deixar rastro na conversa, senão o modelo
 * segue achando que a escrita está de pé.
 */
public record ConfirmRequestDTO(String sessionId, String actionId, boolean approved) {
}
