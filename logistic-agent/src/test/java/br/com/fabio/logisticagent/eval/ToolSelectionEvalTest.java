package br.com.fabio.logisticagent.eval;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.dto.render.TableContent;
import br.com.fabio.logisticagent.service.ChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval de seleção de tools: mede se o modelo escolhe a ferramenta certa, com os argumentos certos,
 * e se a resposta que sai disso está de acordo com o que o system prompt promete.
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
 * <p>Cada caso é avaliado em até sete dimensões (tool esperada, tool proibida, ausência de tool,
 * argumentos, número de chamadas, render e texto da resposta) e só passa se todas fecharem —
 * chamar a tool certa com o filtro errado é resposta errada, não acerto parcial.
 *
 * <p>O dataset é quase todo de leitura. Os casos de escrita ({@code create-vehicle-calls-tool},
 * {@code create-two-drivers-registers-one}) não sujam mais o banco: a escrita passa por confirmação
 * do usuário e, sem ninguém clicando em confirmar, ela fica registrada como pendência e nunca é
 * executada. O que esses casos medem é a chamada de tool certa, a pendência certa e a ausência de
 * "cadastrado com sucesso" na resposta — o modelo não pode dar por feito o que ainda depende do
 * usuário.
 *
 * <p>Todo trecho do system prompt que descreve comportamento (não usar executeQuery no lugar de tool
 * tipada, não suportar exclusão, não confirmar ação sem chamada de tool, traduzir status) deve ter
 * caso aqui. Regra de prompt sem caso é regra que ninguém percebe quando para de valer.
 *
 * <p>O assert é sobre a <b>taxa de acerto</b> do dataset, não sobre cada caso: com LLM, um caso
 * isolado falha por ruído e um assert exato deixaria o build vermelho de forma aleatória. O dataset
 * tem casos difíceis de propósito — 100% não é o esperado, e um piso alto demais é convite a
 * afrouxar o dataset. Ajuste com {@code -Deval.threshold=0.9}.
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

    private static final double DEFAULT_THRESHOLD = 0.75;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ToolCallRecorder recorder;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private Environment environment;

    @Autowired
    private JwtDecoder jwtDecoder;

    /**
     * Autentica como eval-user antes de rodar (ver EvalAuthentication) e desfaz depois: a perna
     * agent -> /mcp exige token para qualquer tool de escrita, e a lista de tools que o modelo vê
     * já sai filtrada pela role de quem chama.
     */
    @BeforeEach
    void authenticateAsEvalUser() {
        EvalAuthentication.authenticateEvalUser(jwtDecoder);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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
            return evaluate(evalCase, response);
        } catch (Exception e) {
            return new Result(evalCase, recorder.calls(), "none", "erro: " + e.getMessage(), false);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private Result evaluate(EvalCase evalCase, ChatMessageDTO response) {
        List<ToolCall> calls = recorder.calls();
        List<String> names = recorder.names();
        List<String> failures = new ArrayList<>();

        List<String> expected = evalCase.expectAnyOfOrEmpty();
        if (!expected.isEmpty() && expected.stream().noneMatch(names::contains)) {
            failures.add("esperava uma de " + expected);
        }

        evalCase.forbidOrEmpty().stream()
                .filter(names::contains)
                .forEach(forbidden -> failures.add("chamou " + forbidden + ", que era proibido"));

        if (evalCase.expectsNoTool() && !names.isEmpty()) {
            failures.add("não devia chamar tool nenhuma, chamou " + names);
        }

        if (evalCase.maxCalls() != null && names.size() > evalCase.maxCalls()) {
            failures.add("fez " + names.size() + " chamadas, o teto do caso é " + evalCase.maxCalls());
        }

        // os argumentos só são cobrados das tools que o caso espera; se ele não espera nenhuma
        // em particular, valem os argumentos de tudo que foi chamado
        String arguments = recorder.argumentsOf(expected.isEmpty() ? names : expected);
        evalCase.expectArgsOrEmpty().stream()
                .filter(fragment -> !containsAnyAlternative(arguments, fragment))
                .forEach(fragment -> failures.add("argumentos sem " + fragment));
        evalCase.forbidArgsOrEmpty().stream()
                .filter(fragment -> arguments.contains(normalizeArgs(fragment)))
                .forEach(fragment -> failures.add("argumentos com " + fragment + ", que não podia aparecer"));

        String expectedRender = evalCase.render();
        String actualRender = renderTypeOf(response.renderData());
        if (expectedRender != null && !expectedRender.equals(actualRender)) {
            failures.add("render esperado '" + expectedRender + "', obtido '" + actualRender + "'");
        }

        if (evalCase.chartType() != null) {
            String actualChartType = response.renderData() instanceof ChartContent chart ? chart.chartType() : null;
            if (actualChartType == null || !evalCase.chartType().equalsIgnoreCase(actualChartType)) {
                failures.add("gráfico esperado do tipo '" + evalCase.chartType() + "', obtido '" + actualChartType + "'");
            }
        }

        if (!evalCase.expectColumnsOrEmpty().isEmpty()) {
            List<String> actualColumns = response.renderData() instanceof TableContent table ? table.columns() : null;
            if (actualColumns == null || !actualColumns.stream().map(column -> column.toLowerCase(Locale.ROOT)).toList()
                    .equals(evalCase.expectColumnsOrEmpty().stream().map(column -> column.toLowerCase(Locale.ROOT)).toList())) {
                failures.add("colunas esperadas " + evalCase.expectColumnsOrEmpty() + ", obtidas " + actualColumns);
            }
        }

        // texto avaliado = resposta + payload de render: a tradução de status vale para a tabela
        // e para o gráfico também, não só para o texto corrido
        String text = (nullToEmpty(response.content()) + " " + serialize(response.renderData()))
                .toLowerCase(Locale.ROOT);
        evalCase.expectTextOrEmpty().stream()
                .filter(fragment -> !text.contains(fragment.toLowerCase(Locale.ROOT)))
                .forEach(fragment -> failures.add("resposta sem '" + fragment + "'"));
        evalCase.forbidTextOrEmpty().stream()
                .filter(fragment -> text.contains(fragment.toLowerCase(Locale.ROOT)))
                .forEach(fragment -> failures.add("resposta com '" + fragment + "', que não podia aparecer"));

        // Escrita não executa na chamada do modelo: ela vira pendência para o usuário confirmar.
        // O que o caso mede aqui é se a resposta registrou a escrita certa — e só uma.
        String actualPending = response.pendingAction() == null ? "none" : response.pendingAction().tool();
        if (evalCase.pendingAction() != null && !evalCase.pendingAction().equals(actualPending)) {
            failures.add("ação pendente esperada '" + evalCase.pendingAction() + "', obtida '" + actualPending + "'");
        }

        return new Result(evalCase, calls, actualRender, String.join("; ", failures), failures.isEmpty());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String serialize(RenderableContent renderData) {
        if (renderData == null) {
            return "";
        }
        try {
            return new ObjectMapper().writeValueAsString(renderData);
        } catch (JsonProcessingException e) {
            return renderData.toString();
        }
    }

    /**
     * Um fragmento pode trazer alternativas separadas por "||" — basta uma casar. Existe porque a
     * mesma coisa se escreve de mais de um jeito em SQL: filtrar o motorista por "d.id =" ou por
     * "r.driver_id =" é a mesma chave, dos dois lados do join, e cobrar só uma das formas reprova
     * uma query correta.
     */
    private static boolean containsAnyAlternative(String arguments, String fragment) {
        return Arrays.stream(fragment.split("\\|\\|"))
                .map(String::trim)
                .filter(alternative -> !alternative.isEmpty())
                .anyMatch(alternative -> arguments.contains(normalizeArgs(alternative)));
    }

    private static String normalizeArgs(String fragment) {
        return fragment.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
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
        List<EvalCase> cases;
        try (var input = new ClassPathResource("eval/tool-selection.json").getInputStream()) {
            cases = mapper.readerForListOf(EvalCase.class).readValue(input);
        }
        return filterByIds(cases);
    }

    /**
     * Recorte do dataset por id ({@code -Deval.cases=id1,id2}). O dataset inteiro é caro — cada caso
     * é ao menos uma ida à LLM, em série, e os casos com setup são duas. Ao mexer numa regra
     * específica, rode só os casos dela; o dataset completo fica para antes de commitar e para o CI.
     */
    private List<EvalCase> filterByIds(List<EvalCase> cases) {
        String selection = System.getProperty("eval.cases", "").trim();
        if (selection.isEmpty()) {
            return cases;
        }
        List<String> ids = Arrays.stream(selection.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
        List<EvalCase> selected = cases.stream().filter(evalCase -> ids.contains(evalCase.id())).toList();
        assertThat(selected)
                .as("nenhum caso do dataset casa com -Deval.cases=%s (ids disponíveis: %s)",
                        selection, cases.stream().map(EvalCase::id).toList())
                .isNotEmpty();
        return selected;
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
                    .append(String.format("%-30s", result.evalCase().id()))
                    .append("tools=").append(result.calls().stream().map(ToolCall::name).toList())
                    .append(" render=").append(result.render());
            if (!result.passed()) {
                out.append("\n           <- ").append(result.detail());
                result.calls().forEach(call -> out.append("\n              ").append(call));
            }
            out.append('\n');
        }
        long passed = results.stream().filter(Result::passed).count();
        out.append(String.format("%nacerto: %d/%d (%.0f%%) | piso: %.0f%%%n",
                passed, results.size(), 100.0 * passed / results.size(), 100 * threshold()));
        return out.toString();
    }

    private record Result(EvalCase evalCase, List<ToolCall> calls, String render, String detail, boolean passed) {
    }
}
