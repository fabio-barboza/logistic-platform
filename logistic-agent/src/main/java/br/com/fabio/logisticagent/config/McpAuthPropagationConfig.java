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

@Configuration
public class McpAuthPropagationConfig {

    @Bean
    McpClientCustomizer<McpClient.SyncSpec> mcpAuthContextCustomizer(TokenExchangeService exchange) {
        return (name, spec) -> spec.transportContextProvider(() -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof JwtAuthenticationToken jwt)) {

                return McpTransportContext.EMPTY;
            }
            return McpTransportContext.create(
                    Map.of("Authorization", "Bearer " + exchange.exchangeFor(jwt.getToken().getTokenValue())));
        });
    }

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
