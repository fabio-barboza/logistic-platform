package br.com.fabio.logisticagent.eval;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Checa, ANTES de o Spring subir o contexto, que o ambiente do eval está de pé:
 * a logistic-api (de onde vêm as tools MCP) e a LLM.
 *
 * <p>Roda como {@link ExecutionCondition} de propósito: o JUnit avalia condições antes dos
 * callbacks que carregam o contexto, então o erro sai como uma frase acionável em vez de um
 * "Failed to load ApplicationContext" de trinta linhas.
 *
 * <p>Falha em vez de pular: quem roda {@code -Peval} está pedindo o eval explicitamente, e um
 * skip verde esconderia que nada foi medido.
 */
public class EvalEnvironmentCondition implements ExecutionCondition {

    private static final String API_URL = System.getProperty("eval.api.url", "http://localhost:8081");
    private static final String LLM_URL = System.getProperty("eval.llm.url", "http://localhost:8200");

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
}
