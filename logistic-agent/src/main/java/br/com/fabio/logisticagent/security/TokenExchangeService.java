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

@Service
public class TokenExchangeService {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeService.class);

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private static final Duration EXPIRY_SLACK = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final JwtDecoder jwtDecoder;
    private final JsonMapper jsonMapper;
    private final String clientId;
    private final String clientSecret;
    private final String targetAudience;

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

            log.warn("Não foi possível decodificar o token de entrada para chave de cache: {}", e.getMessage());
            return subjectToken;
        }
    }

    private void purgeExpired(Instant now) {
        cache.values().removeIf(cached -> !cached.usableAt(now));
    }
}
