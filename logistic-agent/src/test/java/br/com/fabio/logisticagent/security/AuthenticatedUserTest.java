package br.com.fabio.logisticagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void subIsNullWithoutAuthentication() {
        assertThat(AuthenticatedUser.sub()).isNull();
    }

    @Test
    void subComesFromTheAuthenticatedJwt() {
        authenticateAs("user-1");

        assertThat(AuthenticatedUser.sub()).isEqualTo("user-1");
    }

    /**
     * O ponto central do isolamento: o mesmo sessionId, vindo de dois usuários diferentes,
     * tem que virar duas chaves de conversa diferentes — senão o sessionId de um resolveria a
     * conversa (ou a pendência) do outro.
     */
    @Test
    void sameSessionIdYieldsDifferentConversationIdsForDifferentUsers() {
        authenticateAs("user-1");
        String forUser1 = AuthenticatedUser.conversationId("sessao-1");

        authenticateAs("user-2");
        String forUser2 = AuthenticatedUser.conversationId("sessao-1");

        assertThat(forUser1).isNotEqualTo(forUser2);
    }

    @Test
    void sameUserAndSessionIdYieldTheSameConversationId() {
        authenticateAs("user-1");

        assertThat(AuthenticatedUser.conversationId("sessao-1"))
                .isEqualTo(AuthenticatedUser.conversationId("sessao-1"));
    }

    /** Fora de requisição autenticada, a chave é o sessionId cru — não há usuário para isolar. */
    @Test
    void conversationIdFallsBackToRawSessionIdWithoutAuthentication() {
        assertThat(AuthenticatedUser.conversationId("sessao-1")).isEqualTo("sessao-1");
    }
}
