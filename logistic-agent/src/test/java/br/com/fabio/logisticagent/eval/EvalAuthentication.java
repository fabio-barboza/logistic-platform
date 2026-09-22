package br.com.fabio.logisticagent.eval;

import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;

final class EvalAuthentication {

    private static final String TOKEN_URI = System.getProperty("eval.keycloak.token-uri",
            "http://localhost:8090/realms/logistic/protocol/openid-connect/token");
    private static final String CLIENT_ID = "logistic-eval";
    private static final String CLIENT_SECRET = "logistic-eval-secret";
    private static final String USERNAME = "eval-user";
    private static final String PASSWORD = "eval-user";

    private EvalAuthentication() {
    }

    static void authenticateEvalUser(JwtDecoder jwtDecoder) {
        Jwt jwt = jwtDecoder.decode(fetchAccessToken());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authoritiesOf(jwt)));
    }

    private static String fetchAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", CLIENT_ID);
        form.add("client_secret", CLIENT_SECRET);
        form.add("username", USERNAME);
        form.add("password", PASSWORD);

        Map<?, ?> body = RestClient.builder().baseUrl(TOKEN_URI).build().post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        Object accessToken = body == null ? null : body.get("access_token");
        if (!(accessToken instanceof String token)) {
            throw new IllegalStateException(
                    "Eval abortado: Keycloak não devolveu access_token para eval-user em " + TOKEN_URI);
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> authoritiesOf(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Collection<String> roles = (Collection<String>) realmAccess.getOrDefault("roles", List.of());
        return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}
