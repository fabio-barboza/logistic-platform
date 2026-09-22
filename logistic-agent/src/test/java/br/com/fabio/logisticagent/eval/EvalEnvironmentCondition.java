package br.com.fabio.logisticagent.eval;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

public class EvalEnvironmentCondition implements ExecutionCondition {

    private static final String API_URL = System.getProperty("eval.api.url", "http://localhost:8081");
    private static final String LLM_URL = System.getProperty("eval.llm.url", "http://localhost:8200");
    private static final String KEYCLOAK_URL = System.getProperty("eval.keycloak.url", "http://localhost:8090");

    private static final String POSTGRES_HOST_PORT = System.getProperty("eval.postgres.url", "localhost:5432");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (!reachable(API_URL + "/actuator/health")) {
            throw new IllegalStateException("""

                    Eval abortado: logistic-api não respondeu em %s.
                    As tools avaliadas são descobertas por MCP na API — sem ela, não há o que medir.
                    Suba a stack com ./start.sh (ou aponte para outra com -Deval.api.url=<url>).
                    """.formatted(API_URL));
        }
        if (!reachable(LLM_URL + "/v1/models")) {
            throw new IllegalStateException("""

                    Eval abortado: LLM não respondeu em %s.
                    Suba o modelo configurado em spring.ai.openai.base-url e rode de novo
                    (ou aponte para outro com -Deval.llm.url=<url>).
                    """.formatted(LLM_URL));
        }

        if (!reachable(KEYCLOAK_URL + "/realms/logistic/.well-known/openid-configuration")) {
            throw new IllegalStateException("""

                    Eval abortado: Keycloak não respondeu em %s.
                    O eval se autentica como eval-user (EvalAuthenticationExtension) antes de rodar —
                    sem o Keycloak, nem o contexto do Spring sobe (o JwtDecoder resolve o issuer no boot).
                    Suba a stack com ./start.sh (ou aponte para outro com -Deval.keycloak.url=<url>).
                    """.formatted(KEYCLOAK_URL));
        }

        if (!reachableTcp(POSTGRES_HOST_PORT)) {
            throw new IllegalStateException("""

                    Eval abortado: Postgres não respondeu em %s.
                    O contexto Spring completo do agent agora exige datasource (ChatMemory,
                    IPendingActionStore e IConversationStateStore vivem no banco).
                    Suba a stack com ./start.sh (ou aponte para outro com -Deval.postgres.url=<host:porta>).
                    """.formatted(POSTGRES_HOST_PORT));
        }
        return ConditionEvaluationResult.enabled("ambiente do eval disponível");
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
            connection.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean reachableTcp(String hostPort) {
        String[] parts = hostPort.split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 5432;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
