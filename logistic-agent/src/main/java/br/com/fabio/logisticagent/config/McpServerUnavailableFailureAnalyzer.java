package br.com.fabio.logisticagent.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

import java.net.ConnectException;

/**
 * Troca o stacktrace de ~490 linhas do Spring por uma mensagem curta quando o logistic-api
 * está fora do ar. O cliente MCP conecta de forma eager no startup, então a falha aparece
 * como erro de criação do bean mcpSyncClients, com a causa real (ConnectException) enterrada
 * no fim da cadeia.
 *
 * Registrado em META-INF/spring.factories — analisadores de falha rodam antes do contexto
 * subir, então não podem ser beans.
 */
public class McpServerUnavailableFailureAnalyzer extends AbstractFailureAnalyzer<ConnectException> {

    private static final String URL_PROPERTY = "spring.ai.mcp.client.streamable-http.connections.logistic.url";
    private static final String ENDPOINT_PROPERTY = "spring.ai.mcp.client.streamable-http.connections.logistic.endpoint";

    private final Environment environment;

    // O Spring Boot 4 injeta o Environment pelo construtor (SpringFactoriesLoader.ArgumentResolver);
    // a interface EnvironmentAware não é mais aplicada em FailureAnalyzer.
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

        // O ConnectException vindo do cliente HTTP costuma chegar sem mensagem — por isso o texto
        // não depende dela.
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
