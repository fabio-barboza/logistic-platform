package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.security.TokenExchangeService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;

/**
 * Leva o token trocado (aud=logistic-api, ver {@link TokenExchangeService}) até o header
 * Authorization das chamadas MCP do agent para a API. Duas peças, as duas necessárias.
 */
@Configuration
public class McpAuthPropagationConfig {

    /**
     * Coloca o token no contexto de transporte do cliente MCP síncrono.
     *
     * <p>Verificado no bytecode do McpSyncClient: {@code callTool} faz
     * {@code delegate.callTool(...).contextWrite(ctx -> ctx.put(MCP_TRANSPORT_CONTEXT,
     * contextProvider.get())).block()}. A função do contextWrite roda na <b>subscrição</b>, que
     * com {@code .block()} é a thread chamadora — a mesma thread do servlet onde o
     * SecurityContextHolder está preenchido. É o único ponto do caminho MCP onde esse
     * ThreadLocal ainda vale; a tool, do lado do servidor, já roda em outra thread — é por isso
     * que a autorização lá usa {@code McpTransportContext}, e não {@code @PreAuthorize}.
     */
    @Bean
    McpClientCustomizer<McpClient.SyncSpec> mcpAuthContextCustomizer(TokenExchangeService exchange) {
        return (name, spec) -> spec.transportContextProvider(() -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof JwtAuthenticationToken jwt)) {
                // Handshake do startup (initialize/tools-list): não há usuário. Contexto vazio,
                // sem header, e o permitAll do /mcp na API deixa passar.
                return McpTransportContext.EMPTY;
            }
            return McpTransportContext.create(
                    Map.of("Authorization", "Bearer " + exchange.exchangeFor(jwt.getToken().getTokenValue())));
        });
    }

    /**
     * Tira o token do contexto de transporte e põe no header HTTP de fato.
     *
     * <p>O genérico do bean precisa bater exatamente com o que o
     * StreamableHttpHttpClientTransportAutoConfiguration injeta
     * ({@code ObjectProvider<McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>>}) —
     * senão o customizer não é aplicado, e a falha é silenciosa: o header simplesmente não vai.
     */
    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpAuthHeaderCustomizer() {
        return (name, builder) -> builder.httpRequestCustomizer(
                (request, method, uri, body, context) -> {
                    Object header = context == null ? null : context.get("Authorization");
                    if (header instanceof String value) {
                        request.header("Authorization", value);
                    }
                });
    }
}
