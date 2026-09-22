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

@Component
public class McpAuthorization {

    private final JwtDecoder jwtDecoder;

    public McpAuthorization(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

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
