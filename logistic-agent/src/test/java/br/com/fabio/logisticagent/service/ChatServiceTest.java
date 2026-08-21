package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.tool.RenderHolder;
import br.com.fabio.logisticagent.tool.ToolCallHolder;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final String CLAIM = "Aqui está o gráfico de falhas por motorista.";
    private static final ChartContent CHART = new ChartContent("Falhas", "bar", List.of("SC"),
            List.of(new Dataset("Falhas", List.of(11))));

    private final AtomicInteger llmCalls = new AtomicInteger();

    private RenderHolder renderHolder;
    private ToolCallHolder toolCallHolder;
    private ChatClient chatClient;
    private ChatService chatService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        renderHolder = new RenderHolder();
        toolCallHolder = new ToolCallHolder();
        // Caso normal: o modelo consultou o banco antes de responder. Os testes de render partem
        // daí, senão cada resposta com número cairia também no retry de dado sem tool.
        toolCallHolder.register("executeQuery");
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        chatService = new ChatService(chatClient, renderHolder, toolCallHolder, tracerProvider);
    }

    @SuppressWarnings("unchecked")
    private OngoingStubbing<String> whenLlmAnswers() {
        return when(chatClient.prompt().user(any(String.class)).advisors(any(Consumer.class))
                .call().content());
    }

    /** Answer que conta as chamadas à LLM — verify na cadeia de deep stubs registra invocações próprias. */
    private Answer<String> counting(Answer<String> answer) {
        return invocation -> {
            llmCalls.incrementAndGet();
            return answer.answer(invocation);
        };
    }

    @Test
    void claimWithoutRenderTriggersCorrectiveRetry() {
        // Primeira resposta anuncia o gráfico sem chamar a tool; na segunda o modelo renderiza.
        whenLlmAnswers()
                .thenAnswer(counting(invocation -> CLAIM))
                .thenAnswer(counting(invocation -> {
                    renderHolder.set(CHART);
                    return "Aqui está o gráfico.";
                }));

        ChatMessageDTO response = chatService.respond("gere um gráfico de pizza", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
        assertThat(response.content()).isEqualTo("Aqui está o gráfico.");
        assertThat(llmCalls).hasValue(2);
    }

    @Test
    void secondCorrectionRunsWhenFirstRetryStillDoesNotRender() {
        whenLlmAnswers()
                .thenAnswer(counting(invocation -> CLAIM))
                .thenAnswer(counting(invocation -> CLAIM))
                .thenAnswer(counting(invocation -> {
                    renderHolder.set(CHART);
                    return "Aqui está o gráfico.";
                }));

        ChatMessageDTO response = chatService.respond("gere um gráfico de pizza", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
        assertThat(llmCalls).hasValue(3);
    }

    @Test
    void claimWithoutRenderStopsAfterTwoCorrections() {
        whenLlmAnswers().thenAnswer(counting(invocation -> CLAIM));

        ChatMessageDTO response = chatService.respond("gere um gráfico de pizza", "sessao-1");

        assertThat(response.renderData()).isNull();
        assertThat(response.content()).isEqualTo(CLAIM);
        assertThat(llmCalls).hasValue(3);
    }

    @Test
    void rejectedRenderAppendsNoticeToContent() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            renderHolder.registerRejection("O dataset 'Falhas' tem 4 valores, mas labels tem 25 rótulos.");
            return CLAIM;
        }));

        ChatMessageDTO response = chatService.respond("um gráfico", "sessao-1");

        assertThat(response.renderData()).isNull();
        assertThat(response.content())
                .startsWith(CLAIM)
                .contains("Nada foi renderizado")
                .contains("25 rótulos");
    }

    @Test
    void successfulRenderKeepsContentUntouchedAndSkipsRetry() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            renderHolder.set(CHART);
            return CLAIM;
        }));

        ChatMessageDTO response = chatService.respond("um gráfico", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
        assertThat(response.content()).isEqualTo(CLAIM);
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void plainTextAnswerSkipsRetry() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 pedidos entregues em SP."));

        ChatMessageDTO response = chatService.respond("quantos pedidos entregues em SP?", "sessao-1");

        assertThat(response.renderData()).isNull();
        assertThat(response.content()).isEqualTo("Há 42 pedidos entregues em SP.");
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void renderIsBlockedWhenUserDidNotAskForVisualization() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "A taxa de falha em SP é 18,8%."));

        ChatMessageDTO response = chatService.respond("qual a taxa de falha de entrega por estado?", "sessao-1");

        assertThat(renderHolder.isRenderAllowed()).isFalse();
        assertThat(response.renderData()).isNull();
        assertThat(llmCalls).hasValue(1);
    }

    /** Sem render permitido, "posso mostrar em gráfico?" não pode disparar o retry corretivo. */
    @Test
    void offerOfChartDoesNotTriggerRetryWhenRenderIsBlocked() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A taxa de falha em SP é 18,8%. Posso mostrar isso em gráfico, se quiser."));

        ChatMessageDTO response = chatService.respond("qual a taxa de falha por estado?", "sessao-1");

        assertThat(response.renderData()).isNull();
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void markdownTableIsStrippedWhenResponseHasRender() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            renderHolder.set(CHART);
            return """
                    Aqui está o gráfico:

                    | Estado | Falhas |
                    |--------|--------|
                    | SC     | 11     |

                    SC lidera as falhas.""";
        }));

        ChatMessageDTO response = chatService.respond("faça um gráfico de falhas por estado", "sessao-1");

        assertThat(response.content())
                .doesNotContain("|")
                .startsWith("Aqui está o gráfico:")
                .endsWith("SC lidera as falhas.");
    }

    /** "sim" logo depois de o agente oferecer o gráfico é pedido de visualização. */
    @Test
    void yesAfterVisualOfferAllowsRender() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A taxa de falha em SP é 18,8%. Posso mostrar isso em gráfico, se quiser."));
        chatService.respond("qual a taxa de falha por estado?", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> {
            assertThat(renderHolder.isRenderAllowed()).isTrue();
            renderHolder.set(CHART);
            return "Aqui está o gráfico.";
        }));
        ChatMessageDTO response = chatService.respond("sim", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
    }

    /** Sem oferta pendente, "sim" não libera render — nem vira retry corretivo. */
    @Test
    void yesWithoutPendingOfferKeepsRenderBlocked() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 pedidos entregues em SP."));
        chatService.respond("quantos pedidos entregues em SP?", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> {
            assertThat(renderHolder.isRenderAllowed()).isFalse();
            return "Certo.";
        }));
        chatService.respond("sim", "sessao-1");
    }

    /** A oferta vale uma vez: aceita, some. */
    @Test
    void visualOfferIsConsumedByTheAcceptance() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Posso mostrar isso em gráfico, se quiser."));
        chatService.respond("qual a taxa de falha por estado?", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> {
            renderHolder.set(CHART);
            return "Aqui está o gráfico.";
        }));
        chatService.respond("sim", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> {
            assertThat(renderHolder.isRenderAllowed()).isFalse();
            return "Há 42 pedidos.";
        }));
        chatService.respond("sim", "sessao-1");
    }

    /**
     * Dado sem tool: o follow-up "e em MG?" devolveu 106 onde havia 423, com o log de tool calls
     * vazio. A correção é refazer exigindo a consulta.
     */
    @Test
    void dataWithoutToolCallTriggersCorrectiveRetry() {
        toolCallHolder.reset();
        whenLlmAnswers()
                .thenAnswer(counting(invocation -> "São 106 pedidos entregues em MG."))
                .thenAnswer(counting(invocation -> {
                    toolCallHolder.register("executeQuery");
                    return "São 423 pedidos entregues em MG.";
                }));

        ChatMessageDTO response = chatService.respond("e em MG?", "sessao-1");

        assertThat(response.content()).isEqualTo("São 423 pedidos entregues em MG.");
        assertThat(llmCalls).hasValue(2);
    }

    /** Duas correções e para: o loop de tool calls não pode ficar preso no modelo teimoso. */
    @Test
    void dataWithoutToolCallStopsAfterTwoCorrections() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation -> "São 106 pedidos entregues em MG."));

        chatService.respond("e em MG?", "sessao-1");

        assertThat(llmCalls).hasValue(3);
    }

    /** Recusa e conversa não têm número: não há dado a desmentir, não se refaz. */
    @Test
    void answerWithoutNumbersDoesNotTriggerDataRetry() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A plataforma não suporta exclusão de registros."));

        ChatMessageDTO response = chatService.respond("apague o veículo", "sessao-1");

        assertThat(response.content()).isEqualTo("A plataforma não suporta exclusão de registros.");
        assertThat(llmCalls).hasValue(1);
    }

    /**
     * "Transforme isso num gráfico" reaproveita os dados do turno anterior por decisão do usuário:
     * há render, não há tool de dados, e está certo assim.
     */
    @Test
    void renderReusingPreviousDataDoesNotTriggerDataRetry() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            renderHolder.set(CHART);
            return "Aqui está o gráfico com os 11 registros.";
        }));

        ChatMessageDTO response = chatService.respond("transforme isso num gráfico", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
        assertThat(llmCalls).hasValue(1);
    }
}
