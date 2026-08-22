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

/**
 * Autentica o eval como usuário de máquina antes de rodar — obtém um token real via direct grant
 * no client {@code logistic-eval} (usuário {@code eval-user}) e instala um
 * {@link JwtAuthenticationToken} no {@link SecurityContextHolder}.
 *
 * <p>A tool MCP nega toda chamada sem token (McpAuthorization, na logistic-api), e a lista de
 * tools que o modelo vê já sai filtrada pela role de quem está "logado" — sem isto, todo caso de
 * escrita do dataset falharia por falta de tool, não por escolha errada do modelo. eval-user tem a
 * role {@code admin} (chat+read+write compostas), então nenhum caso perde tool por permissão e o
 * {@code tool-selection.json} não muda.
 *
 * <p><b>Não é um perfil que desliga a segurança</b> — é o eval se
 * autenticando de verdade, como qualquer outro chamador: o token passa pelo mesmo
 * {@link JwtDecoder} (issuer + audience) que valida requisições reais.
 *
 * <p>O client {@code logistic-eval} precisa do mesmo protocol mapper de audiência que o
 * {@code logistic-webui} tem para {@code logistic-agent} — sem ele, o token de eval-user não tem
 * {@code aud} nenhum (Keycloak não inclui audiência por padrão sem um mapper) e o JwtDecoder do
 * agent, que exige {@code aud=logistic-agent}, rejeita antes de qualquer coisa rodar.
 */
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

    /** Mesma extração de SecurityConfig.realmRolesGrantedAuthoritiesConverter — manter em sincronia. */
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
