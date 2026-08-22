package br.com.fabio.logisticagent.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Identidade do usuário autenticado nesta thread (SecurityContextHolder), lida direto do JWT —
 * mesmo padrão que {@code TokenExchangeService} e {@code McpAuthPropagationConfig} já usam para o
 * token em si. Compartilhada entre {@code ChatService} e {@code ConfirmationService}: os dois
 * precisam da mesma chave de conversa, e duplicar a leitura do {@code JwtAuthenticationToken} em
 * cada um divergiria cedo ou tarde.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /** {@code sub} do JWT autenticado nesta thread, ou null fora de requisição HTTP autenticada. */
    public static String sub() {
        Object auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    /**
     * Chave de conversa isolada por usuário: {@code sub} + o sessionId que o cliente mandou.
     *
     * <p>O sessionId nasce no JavaScript ({@code generateSessionId()} no main.js) e o agent
     * confiava nele como chave única da ChatMemory e do PendingActionStore. Com autenticação,
     * mandar o sessionId de outra pessoa passaria a ler (ou resgatar) a conversa/pendência dela —
     * o sessionId sozinho não distingue usuários. Prefixar pelo sub fecha isso: o id de outro
     * usuário simplesmente não resolve, porque o sub dele é outro.
     *
     * <p>Sem sub (fora de requisição HTTP autenticada — hoje só o eval antes de se autenticar, ou
     * um teste que não monta SecurityContext), a chave é o sessionId cru: mantém o comportamento
     * de antes da autenticação, onde não havia usuário para isolar.
     */
    public static String conversationId(String sessionId) {
        String sub = sub();
        return sub == null ? sessionId : sub + "|" + sessionId;
    }
}
