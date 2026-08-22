package br.com.fabio.logisticagent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Troca o token do usuário (aud=logistic-agent) por um com aud=logistic-api, via RFC 8693
 * (token exchange), para o agent falar com o /mcp da API sem fazer <i>token passthrough</i> —
 * proibido pela spec de autorização do MCP ("MCP servers MUST NOT accept or transit any other
 * tokens"), que é o vetor do confused deputy.
 *
 * <p><b>Peça de configuração que não é óbvia por código nenhum:</b> o Keycloak só coloca
 * {@code logistic-api} no {@code aud} do token trocado se o client <b>logistic-agent</b> (quem
 * pede a troca, não o alvo) tiver um protocol mapper de audience apontando para
 * {@code logistic-api} — mesmo padrão do mapper que {@code logistic-webui} já tem para
 * {@code logistic-agent} (ver {@code infra/keycloak/import/logistic-realm.json}). Faltando isso,
 * o Keycloak devolve 400 {@code invalid_request: "Requested audience not available: logistic-api"}
 * mesmo com {@code standard.token.exchange.enabled=true} nos dois clients — verificado no
 * bytecode do {@code StandardTokenExchangeProvider} (Keycloak 26.7): o exchange client-para-client
 * usa as <i>próprias</i> client scopes de quem pede a troca para montar o token resultante, não
 * as do client alvo. Marcar {@code standard.token.exchange.enabled} no {@code logistic-api}
 * (o alvo) não é necessário — só no requisitante.
 */
@Service
public class TokenExchangeService {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeService.class);

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    /**
     * O token trocado só é reaproveitado do cache com essa folga até expirar; perto do fim ele
     * expiraria em uso (ex.: no meio de uma chamada de tool), então é tratado como já expirado.
     */
    private static final Duration EXPIRY_SLACK = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final JwtDecoder jwtDecoder;
    private final JsonMapper jsonMapper;
    private final String clientId;
    private final String clientSecret;
    private final String targetAudience;

    /**
     * Chaveado pelo jti (ou sub+exp, se o jti não vier) do token de <b>entrada</b> — nunca pelo
     * sub sozinho, senão o token trocado sobreviveria ao logout do usuário. Igual em espírito ao
     * PendingActionStore: ConcurrentHashMap com purga por TTL, sem trazer biblioteca de cache.
     */
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    private record CachedToken(String value, Instant expiresAt) {
        boolean usableAt(Instant now) {
            return expiresAt.isAfter(now.plus(EXPIRY_SLACK));
        }
    }

    public TokenExchangeService(JwtDecoder jwtDecoder, JsonMapper jsonMapper,
            @Value("${logistic.security.keycloak.token-uri}") String tokenUri,
            @Value("${logistic.security.keycloak.client-id}") String clientId,
            @Value("${logistic.security.keycloak.client-secret}") String clientSecret,
            @Value("${logistic.security.keycloak.target-audience}") String targetAudience) {
        this.jwtDecoder = jwtDecoder;
        this.jsonMapper = jsonMapper;
        this.restClient = RestClient.builder().baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.targetAudience = targetAudience;
    }

    /** @return access token com aud={@code target-audience}, pronto para o header Authorization */
    public String exchangeFor(String subjectToken) {
        Instant now = Instant.now();
        String key = cacheKey(subjectToken, now);

        CachedToken cached = cache.get(key);
        if (cached != null && cached.usableAt(now)) {
            return cached.value();
        }

        CachedToken fresh = exchange(subjectToken);
        cache.put(key, fresh);
        purgeExpired(now);
        return fresh.value();
    }

    private CachedToken exchange(String subjectToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", SUBJECT_TOKEN_TYPE);
        form.add("audience", targetAudience);

        Map<String, Object> body;
        try {
            body = restClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpStatusCodeException e) {
            // O Keycloak devolve error_description útil (token exchange desligado no client,
            // audiência desconhecida, subject token expirado) — vale mais para o usuário, no
            // caminho de erro do chat, do que o stack trace da exceção HTTP.
            throw new RuntimeException("Troca de token falhou: " + errorDescription(e.getResponseBodyAsString()), e);
        }

        String accessToken = body == null ? null : (String) body.get("access_token");
        Number expiresIn = body == null ? null : (Number) body.get("expires_in");
        if (accessToken == null || expiresIn == null) {
            throw new RuntimeException("Troca de token falhou: resposta do Keycloak sem access_token/expires_in");
        }
        return new CachedToken(accessToken, Instant.now().plusSeconds(expiresIn.longValue()));
    }

    private String errorDescription(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "sem corpo na resposta";
        }
        try {
            Map<?, ?> error = jsonMapper.readValue(responseBody, Map.class);
            Object description = error.get("error_description");
            return description != null ? description.toString() : responseBody;
        } catch (JacksonException e) {
            return responseBody;
        }
    }

    private String cacheKey(String subjectToken, Instant now) {
        try {
            Jwt jwt = jwtDecoder.decode(subjectToken);
            String jti = jwt.getId();
            if (jti != null) {
                return jti;
            }
            return jwt.getSubject() + "|" + jwt.getExpiresAt();
        } catch (JwtException e) {
            // Não deveria acontecer — o token já passou pelo resource server para chegar aqui —
            // mas sem chave estável o pior caso é não reaproveitar o cache, não quebrar a troca.
            log.warn("Não foi possível decodificar o token de entrada para chave de cache: {}", e.getMessage());
            return subjectToken;
        }
    }

    private void purgeExpired(Instant now) {
        cache.values().removeIf(cached -> !cached.usableAt(now));
    }
}
