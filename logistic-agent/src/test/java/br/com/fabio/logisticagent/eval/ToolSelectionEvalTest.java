package br.com.fabio.logisticagent.eval;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.dto.render.TableContent;
import br.com.fabio.logisticagent.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval de seleção de tools: mede se o modelo escolhe a ferramenta certa para cada pergunta.
 *
 * <p><b>Não roda no build padrão.</b> Depende de infraestrutura externa — a stack de pé e uma LLM
 * respondendo — então está marcado com {@code @Tag("eval")} e é excluído pelo surefire. Para rodar:
 *
 * <pre>./mvnw test -Peval</pre>
 *
 * <p>Quem roda é responsável por garantir o ambiente: {@code ./start.sh} com a logistic-api
 * respondendo e o modelo servido na {@code spring.ai.openai.base-url}. Faltando qualquer um, o teste
 * falha com mensagem explícita em vez de passar em silêncio.
 *
 * <p>O assert é sobre a <b>taxa de acerto</b> do dataset, não sobre cada caso: com LLM, um caso
 * isolado falha por ruído e um assert exato deixaria o build vermelho de forma aleatória. Ajuste o
 * piso com {@code -Deval.threshold=0.9}.
 */
@Tag("eval")
@ExtendWith(EvalEnvironmentCondition.class)
@SpringBootTest(properties = {
        // determinismo: a temperatura de produção (0.7) faz o mesmo caso alternar entre execuções
        "spring.ai.openai.chat.options.temperature=0"
})
@Import(EvalTestConfig.class)
@DisplayName("Eval — seleção de tools pelo modelo")
class ToolSelectionEvalTest {

    private static final double DEFAULT_THRESHOLD = 0.8;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ToolCallRecorder recorder;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("o modelo escolhe a tool certa na maioria dos casos do dataset")
    void toolSelectionAccuracyIsAboveThreshold() throws IOException {
        List<ToolCallback> discovered = List.of(toolCallbackProvider.getToolCallbacks());
        assertThat(discovered)
                .as("nenhuma tool MCP descoberta — a logistic-api (%s) precisa estar no ar ANTES do agent",
                        environment.getProperty("spring.ai.mcp.client.streamable-http.connections.logistic.url"))
                .isNotEmpty();

        List<EvalCase> cases = loadDataset();
        List<Result> results = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            results.add(run(evalCase));
        }

        System.out.println(report(results, discovered.size()));

        double accuracy = (double) results.stream().filter(Result::passed).count() / results.size();
        assertThat(accuracy)
                .as("taxa de acerto na seleção de tools (dataset com %d casos)", results.size())
                .isGreaterThanOrEqualTo(threshold());
    }

    private Result run(EvalCase evalCase) {
        // cada caso roda numa "requisição" nova: o RenderHolder é request-scoped e precisa
        // nascer limpo, senão o render de um caso vaza para o seguinte
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        recorder.reset();
        String sessionId = UUID.randomUUID().toString();

        try {
            if (evalCase.setup() != null) {
                chatService.respond(evalCase.setup(), sessionId);
                recorder.reset(); // só interessa o que a pergunta avaliada disparou
            }

            ChatMessageDTO response = chatService.respond(evalCase.question(), sessionId);
            return evaluate(evalCase, recorder.calls(), response.renderData());
        } catch (Exception e) {
            return new Result(evalCase, recorder.calls(), "none", "erro: " + e.getMessage(), false);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private Result evaluate(EvalCase evalCase, List<String> calls, RenderableContent renderData) {
        List<String> failures = new ArrayList<>();

        List<String> expected = evalCase.expectAnyOfOrEmpty();
        if (!expected.isEmpty() && expected.stream().noneMatch(calls::contains)) {
            failures.add("esperava uma de " + expected);
        }

        evalCase.forbidOrEmpty().stream()
                .filter(calls::contains)
                .forEach(forbidden -> failures.add("chamou " + forbidden + ", que era proibido"));

        String expectedRender = evalCase.render();
        if (expectedRender != null) {
            String actualRender = renderTypeOf(renderData);
            if (!expectedRender.equals(actualRender)) {
                failures.add("render esperado '" + expectedRender + "', obtido '" + actualRender + "'");
            }
        }

        return new Result(evalCase, calls, renderTypeOf(renderData), String.join("; ", failures), failures.isEmpty());
    }

    private static String renderTypeOf(RenderableContent renderData) {
        return switch (renderData) {
            case ChartContent ignored -> "chart";
            case TableContent ignored -> "table";
            case null -> "none";
        };
    }

    private List<EvalCase> loadDataset() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (var input = new ClassPathResource("eval/tool-selection.json").getInputStream()) {
            return mapper.readerForListOf(EvalCase.class).readValue(input);
        }
    }

    private double threshold() {
        return Double.parseDouble(System.getProperty("eval.threshold", String.valueOf(DEFAULT_THRESHOLD)));
    }

    private String report(List<Result> results, int discoveredTools) {
        StringBuilder out = new StringBuilder("\n=== Eval: seleção de tools ===\n");
        out.append("tools MCP descobertas: ").append(discoveredTools)
                .append(" | modelo: ").append(environment.getProperty("spring.ai.openai.chat.options.model"))
                .append("\n\n");
        for (Result result : results) {
            out.append(result.passed() ? "  PASS  " : "  FAIL  ")
                    .append(String.format("%-24s", result.evalCase().id()))
                    .append("tools=").append(result.calls())
                    .append(" render=").append(result.render());
            if (!result.passed()) {
                out.append("  <- ").append(result.detail());
            }
            out.append('\n');
        }
        long passed = results.stream().filter(Result::passed).count();
        out.append(String.format("%nacerto: %d/%d (%.0f%%) | piso: %.0f%%%n",
                passed, results.size(), 100.0 * passed / results.size(), 100 * threshold()));
        return out.toString();
    }

    private record Result(EvalCase evalCase, List<String> calls, String render, String detail, boolean passed) {
    }
}
