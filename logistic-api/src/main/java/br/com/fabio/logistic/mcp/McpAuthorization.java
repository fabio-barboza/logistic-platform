package br.com.fabio.logistic.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Confere a role exigida por uma tool MCP, a partir do Bearer carregado no
 * {@link McpTransportContext}.
 *
 * <p>A checagem mora aqui, e não num {@code @PreAuthorize} no service, porque a tool executa numa
 * thread do {@code Schedulers.boundedElastic} (ver {@code McpServerFeatures$AsyncToolSpecification}):
 * o {@code SecurityContextHolder}, que é ThreadLocal, está vazio lá. O {@link McpTransportContext}
 * viaja pelo Reactor Context e não depende de thread.
 *
 * <p>Reaproveita o {@link JwtDecoder} já configurado em {@code SecurityConfig} — o mesmo validador de
 * audience da fase 3. É essa validação que impede passthrough: um token do browser
 * (aud=logistic-agent) é recusado aqui.
 */
@Component
public class McpAuthorization {

    private final JwtDecoder jwtDecoder;

    public McpAuthorization(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * Nega lançando {@link McpAuthorizationException}. O texto da mensagem carrega o marcador
     * {@code insufficient_scope} — não um HTTP 403 real, ver a nota em
     * {@code McpAuthorizationException}.
     */
    public void require(McpTransportContext context, String scope) {
        String authorization = header(context);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new McpAuthorizationException(scope);
        }
        Jwt jwt = decode(authorization.substring(7).strip(), scope);
        if (!roles(jwt).contains(scope)) {
            throw new McpAuthorizationException(scope);
        }
    }

    private String header(McpTransportContext context) {
        Object value = context.get(HttpHeaders.AUTHORIZATION);
        return value instanceof String s ? s : null;
    }

    private Jwt decode(String token, String scope) {
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            throw new McpAuthorizationException(scope);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> roles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        Collection<String> roles = (Collection<String>) realmAccess.getOrDefault("roles", List.of());
        return Set.copyOf(roles);
    }
}
