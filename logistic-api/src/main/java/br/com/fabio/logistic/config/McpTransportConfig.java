package br.com.fabio.logistic.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Reconstrói à mão o bean que {@code McpServerStreamableHttpWebMvcAutoConfiguration} monta sozinho
 * (mesmos jsonMapper/mcpEndpoint/keepAliveInterval/disallowDelete, verificado no bytecode dela),
 * porque essa auto-config não aceita um {@link McpTransportContextExtractor} externo — só assim o
 * Authorization chega ao {@link McpTransportContext} dentro das tools MCP. A auto-config declara o
 * bean dela com {@code @ConditionalOnMissingBean}, então este aqui a substitui sem precisar excluí-la.
 */
@Configuration
public class McpTransportConfig {

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(mcpAuthContextExtractor())
                .build();
    }

    private McpTransportContextExtractor<ServerRequest> mcpAuthContextExtractor() {
        return request -> {
            String authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
            return authorization == null
                    ? McpTransportContext.EMPTY
                    : McpTransportContext.create(Map.of(HttpHeaders.AUTHORIZATION, authorization));
        };
    }
}
