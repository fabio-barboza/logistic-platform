package br.com.fabio.logisticagent.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

import java.net.ConnectException;

public class McpServerUnavailableFailureAnalyzer extends AbstractFailureAnalyzer<ConnectException> {

    private static final String URL_PROPERTY = "spring.ai.mcp.client.streamable-http.connections.logistic.url";
    private static final String ENDPOINT_PROPERTY = "spring.ai.mcp.client.streamable-http.connections.logistic.endpoint";

    private final Environment environment;

    public McpServerUnavailableFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConnectException cause) {
        if (!isMcpClientFailure(rootFailure)) {
            return null;
        }

        String url = environment.getProperty(URL_PROPERTY, "http://localhost:8081");
        String endpoint = environment.getProperty(ENDPOINT_PROPERTY, "/mcp");

        String description = "O logistic-agent não conseguiu conectar no servidor MCP do logistic-api em "
                + url + endpoint + ". As tools MCP são carregadas no startup, então o agent não sobe sem elas.";
        String action = "Suba o logistic-api antes do agent:\n"
                + "    cd logistic-platform/logistic-api && ./mvnw spring-boot:run\n"
                + "Confirme que a porta responde: curl " + url + "/actuator/health";

        return new FailureAnalysis(description, action, cause);
    }

    private boolean isMcpClientFailure(Throwable rootFailure) {
        Throwable current = rootFailure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("mcpSyncClients")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
