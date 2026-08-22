package br.com.fabio.logistic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Gêmea da {@code SecurityConfig} do logistic-agent — mesmo conversor de roles e mesmo
 * validador de audience, copiados porque os dois apps não compartilham módulo Maven (ver
 * a primeira linha do CLAUDE.md). Mudou uma, mude a outra.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * O Keycloak põe as roles em {@code realm_access.roles}; o
     * {@code JwtGrantedAuthoritiesConverter} padrão do Spring lê {@code scope}/{@code scp} e
     * não enxerga isso. Sem este conversor, todo usuário chega sem authority nenhuma e toda
     * regra de {@code hasRole} nega. Prefixo {@code ROLE_} porque {@code hasRole("read")}
     * procura exatamente isso.
     */
    private JwtAuthenticationConverter realmRolesConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realmRolesGrantedAuthoritiesConverter());
        return converter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> realmRolesGrantedAuthoritiesConverter() {
        return jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Collection<String> roles = (Collection<String>) realmAccess.getOrDefault("roles", List.of());
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .map(GrantedAuthority.class::cast)
                    .toList();
        };
    }

    /**
     * Validador de audience, composto com o validador padrão do issuer. Aqui a audiência é
     * {@code logistic-api} — diferente da do agent — e é por isso que o token do browser
     * (aud=logistic-agent) não serve aqui: só o token trocado pelo agent passa.
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties props,
            @Value("${logistic.security.audience}") String audience) {
        String issuerUri = props.getJwt().getIssuerUri();
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
        decoder.setJwtValidator(withAudience);
        return decoder;
    }

    /**
     * Nenhuma regra menciona {@code admin}: a role composta do Keycloak já expande em
     * chat/read/write no token — quem tem {@code admin} recebe as três authorities e passa em
     * qualquer uma das checagens abaixo sem código extra aqui.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // O agent faz o handshake MCP no startup, FORA de qualquer requisicao HTTP e sem
                        // usuario nenhum. Exigir autenticacao aqui faria o agent subir sem tools e o chat
                        // responder "erro ao processar" (ver McpServerUnavailableFailureAnalyzer).
                        // A autorizacao real das tools mora dentro delas (McpAuthorization).
                        .requestMatchers("/mcp/**", "/mcp").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                        // O preflight não carrega Authorization.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole("read")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("write")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("write")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("write")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("write")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(realmRolesConverter())))
                .build();
    }
}
