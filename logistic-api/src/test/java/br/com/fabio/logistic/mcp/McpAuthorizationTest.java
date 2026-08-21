package br.com.fabio.logistic.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unitário: sem contexto Spring, sem H2, sem Keycloak. O {@link JwtDecoder} é dublê — a validação
 * real de assinatura/audience é responsabilidade dele (SecurityConfig), não desta classe.
 */
class McpAuthorizationTest {

    private static final String TOKEN = "um-token-jwt-qualquer";

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private McpAuthorization mcpAuthorization;

    @BeforeEach
    void setUp() {
        mcpAuthorization = new McpAuthorization(jwtDecoder);
    }

    private McpTransportContext contextWithBearer(String token) {
        return McpTransportContext.create(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Jwt jwtWithRoles(String... roles) {
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void emptyContextDenies() {
        assertThatThrownBy(() -> mcpAuthorization.require(McpTransportContext.EMPTY, "write"))
                .isInstanceOf(McpAuthorizationException.class);
    }

    @Test
    void tokenWithoutRequiredRoleDenies() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwtWithRoles("read", "chat"));

        assertThatThrownBy(() -> mcpAuthorization.require(contextWithBearer(TOKEN), "write"))
                .isInstanceOf(McpAuthorizationException.class)
                .hasMessageContaining("write");
    }

    @Test
    void tokenWithRequiredRolePasses() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwtWithRoles("read", "write", "chat"));

        assertThatNoException().isThrownBy(() -> mcpAuthorization.require(contextWithBearer(TOKEN), "write"));
    }

    /** Prova a proteção contra passthrough: um token rejeitado pelo JwtDecoder (aud errada,
     * assinatura inválida, expirado) nunca chega a ter as roles conferidas. */
    @Test
    void tokenRejectedByDecoderDenies() {
        when(jwtDecoder.decode(TOKEN)).thenThrow(new JwtException("aud claim inválida"));

        assertThatThrownBy(() -> mcpAuthorization.require(contextWithBearer(TOKEN), "read"))
                .isInstanceOf(McpAuthorizationException.class);
    }

    @Test
    void compositeAdminRoleExpandedToReadAndWritePasses() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwtWithRoles("admin", "read", "write", "chat"));

        assertThatNoException().isThrownBy(() -> mcpAuthorization.require(contextWithBearer(TOKEN), "read"));
        assertThatNoException().isThrownBy(() -> mcpAuthorization.require(contextWithBearer(TOKEN), "write"));
    }

    @Test
    void missingAuthorizationHeaderDenies() {
        McpTransportContext context = McpTransportContext.create(Map.of());

        assertThatThrownBy(() -> mcpAuthorization.require(context, "read"))
                .isInstanceOf(McpAuthorizationException.class);
    }

    @Test
    void nonBearerAuthorizationDenies() {
        McpTransportContext context = McpTransportContext.create(
                Map.of(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));

        assertThatThrownBy(() -> mcpAuthorization.require(context, "read"))
                .isInstanceOf(McpAuthorizationException.class);
    }
}
