package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionMapper;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.tool.QueryResultHolder;
import br.com.fabio.logisticagent.tool.RenderHolder;
import br.com.fabio.logisticagent.tool.RenderTool;
import br.com.fabio.logisticagent.tool.ToolCallHolder;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatServiceTest {

    private static final String CLAIM = "Aqui está o gráfico de falhas por motorista.";
    private static final ChartContent CHART = new ChartContent("Falhas", "bar", List.of("SC"),
            List.of(new Dataset("Falhas", List.of(11))));

    private final AtomicInteger llmCalls = new AtomicInteger();

    private RenderHolder renderHolder;
    private ToolCallHolder toolCallHolder;
    private PendingActionHolder pendingActionHolder;
    private ChatClient chatClient;
    private ChatService chatService;
    private QueryResultHolder queryResults;
    private ChatClient.ChatClientRequestSpec requestSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        renderHolder = new RenderHolder();
        queryResults = new QueryResultHolder();
        toolCallHolder = new ToolCallHolder();
        pendingActionHolder = new PendingActionHolder();

        toolCallHolder.register("executeQuery");
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(any(String.class)).advisors(any(Consumer.class)))
                .thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        chatService = new ChatService(chatClient, renderHolder, new RenderTool(renderHolder, queryResults),
                toolCallHolder, pendingActionHolder,
                new PendingActionMapper(JsonMapper.builder().build()), tracerProvider,
                new InMemoryConversationStateStore());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @SuppressWarnings("unchecked")
    private OngoingStubbing<String> whenLlmAnswers() {
        return when(requestSpec.call().content());
    }

    private Answer<String> counting(Answer<String> answer) {
        return invocation -> {
            llmCalls.incrementAndGet();
            return answer.answer(invocation);
        };
    }

    @Test
    void permissionDeniedToolCallReturnsFriendlyMessage() {
        ToolExecutionException denied = new ToolExecutionException(
                ToolDefinition.builder().name("deleteDriver").description("d").inputSchema("{}").build(),
                new IllegalStateException("Error invoking method: deleteDriver\ninsufficient_scope: requer a role \"write\""));
        whenLlmAnswers().thenThrow(denied);

        ChatMessageDTO response = chatService.respond("exclua o motorista X", "sessao-1");

        assertThat(response.content()).isEqualTo("Você não tem permissão para executar essa operação.");
        assertThat(response.renderData()).isNull();
        assertThat(response.pendingAction()).isNull();
    }

    @Test
    void toolExecutionExceptionWithoutMarkerPropagates() {
        ToolExecutionException other = new ToolExecutionException(
                ToolDefinition.builder().name("createOrder").description("d").inputSchema("{}").build(),
                new IllegalStateException("algum outro erro de negócio"));
        whenLlmAnswers().thenThrow(other);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> chatService.respond("crie um pedido", "sessao-1"))
                .isSameAs(other);
    }

    @Test
    void successfulRenderKeepsContentUntouched() {
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
    void claimWithoutRenderTriggersCorrectiveRetry() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            if (llmCalls.get() > 1) {
                renderHolder.set(CHART);
                return "Aqui está o gráfico em pizza.";
            }
            return "Aqui está o gráfico em pizza.";
        }));

        ChatMessageDTO response = chatService.respond("em pissa", "sessao-1");

        assertThat(response.renderData()).isEqualTo(CHART);
        assertThat(llmCalls).hasValue(2);
    }

    @Test
    void claimWithoutRenderStopsAfterTwoCorrections() {
        whenLlmAnswers().thenAnswer(counting(invocation -> CLAIM));

        ChatMessageDTO response = chatService.respond("um gráfico de falhas", "sessao-1");

        assertThat(response.renderData()).isNull();
        assertThat(llmCalls).hasValue(3);
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

    @Test
    void dataWithoutToolCallStopsAfterTwoCorrectionsAndIsContradicted() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation -> "São 106 pedidos entregues em MG."));

        ChatMessageDTO response = chatService.respond("e em MG?", "sessao-1");

        assertThat(llmCalls).hasValue(3);
        assertThat(response.content())
                .startsWith("São 106 pedidos entregues em MG.")
                .contains("Nenhuma consulta e nenhuma gravação aconteceram");
    }

    @Test
    void writeSuccessClaimWithoutPendingIsContradicted() {
        toolCallHolder.reset();
        toolCallHolder.register("executeQuery");
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "O veículo Truck Y com capacidade de 200 kg foi cadastrado com sucesso."));

        ChatMessageDTO response = chatService.respond("sim, pode cadastrar", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).contains("Nada foi gravado");
        assertThat(llmCalls).hasValue(3);
    }

    @Test
    void firstPersonWriteClaimWithoutPendingIsContradicted() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Pronto, cadastrei o veículo Truck Y."));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck Y", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado");
    }

    @Test
    void readingCreationDateIsNotAWriteClaim() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "O motorista João Silva foi cadastrado em 12/03/2024 e atende a região de Campinas."));

        ChatMessageDTO response = chatService.respond("quando o João Silva entrou?", "sessao-1");

        assertThat(response.content()).doesNotContain("Nada foi gravado");
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void writeRequestThatRegisteredNothingIsAnnouncedAsNotWritten() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "O veículo Truck Z com capacidade de 200 kg está pronto para entrar na frota."));

        ChatMessageDTO response = chatService.respond(
                "cadastre um veículo chamado Truck Z com capacidade 200", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).contains("Nada foi gravado nesta resposta");
    }

    @Test
    void bareYesAfterAWriteRequestIsStillAWriteTurn() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Confirma que a capacidade é 200 kg?"));
        chatService.respond("cadastre um veículo chamado Truck Z com capacidade 200", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> "Pronto! O veículo Truck Z já está na frota."));
        ChatMessageDTO response = chatService.respond("sim", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado nesta resposta");
    }

    @Test
    void questionBackToTheUserIsNotAnnouncedAsNotWritten() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Para cadastrar preciso do e-mail e da data de nascimento. Pode me informar?"));

        ChatMessageDTO response = chatService.respond("cadastre um motorista chamado João", "sessao-1");

        assertThat(response.content()).doesNotContain("Nada foi gravado");
    }

    @Test
    void refusalIsNotAnnouncedAsNotWritten() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A plataforma não suporta exclusão de pedidos."));

        ChatMessageDTO response = chatService.respond("apague o pedido mais antigo", "sessao-1");

        assertThat(response.content()).isEqualTo("A plataforma não suporta exclusão de pedidos.");
    }

    @Test
    void refusalInThePassiveVoiceIsAlsoLeftAlone() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A exclusão de pedidos não é suportada pelo sistema. Não há uma ferramenta "
                        + "disponível para excluir pedidos."));

        ChatMessageDTO response = chatService.respond("apague o pedido mais antigo", "sessao-1");

        assertThat(response.content()).doesNotContain("Nada foi gravado");
    }

    @Test
    void denialMixedWithAWriteClaimIsStillContradicted() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Não posso excluir pedidos, mas cadastrei o veículo Truck Z para você."));

        ChatMessageDTO response = chatService.respond("apague o pedido e cadastre o veículo Truck Z", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado");
    }

    @Test
    void writeIntentDoesNotLeakIntoTheNextQuestion() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Confirma a capacidade?"));
        chatService.respond("cadastre um veículo Truck Z", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 motoristas."));
        ChatMessageDTO response = chatService.respond("quantos motoristas existem?", "sessao-1");

        assertThat(response.content()).isEqualTo("Há 42 motoristas.");
    }

    @Test
    void writeSuccessClaimWithPendingKeepsOnlyThePendingNotice() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-3", "sessao-1", "createVehicle",
                    "{\"name\":\"Truck Y\"}", Instant.now(), Map.of()));
            return "Veículo Truck Y cadastrado com sucesso.";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck Y", "sessao-1");

        assertThat(response.content())
                .contains("Nada foi gravado ainda")
                .doesNotContain("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void questionRepeatingUserNumbersIsNotContradicted() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Deseja cadastrar um novo veículo com esse nome e capacidade de 200 kg?"));

        ChatMessageDTO response = chatService.respond("cadastre um veículo Truck Y com capacidade 200", "sessao-1");

        assertThat(response.content()).doesNotContain("Nenhuma consulta e nenhuma gravação aconteceram");
    }

    @Test
    void unregisteredActionNoticeWinsOverTheUnverifiedDataOne() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A ação foi registrada: 1 veículo de 200 kg. Aguardando sua confirmação."));

        ChatMessageDTO response = chatService.respond("cadastre um veículo de 200 kg", "sessao-1");

        assertThat(response.content())
                .contains("Nenhuma ação foi registrada")
                .doesNotContain("Nenhuma consulta e nenhuma gravação aconteceram");
    }

    @Test
    void answerWithoutNumbersDoesNotTriggerDataRetry() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A plataforma não suporta exclusão de registros."));

        ChatMessageDTO response = chatService.respond("apague o veículo", "sessao-1");

        assertThat(response.content()).isEqualTo("A plataforma não suporta exclusão de registros.");
        assertThat(llmCalls).hasValue(1);
    }

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

    @Test
    void pendingWriteIsReturnedWithNotice() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                    "{\"name\":\"João Silva\",\"state\":\"SP\"}", Instant.now(), Map.of()));
            return "Vou cadastrar o motorista João Silva.";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o motorista João Silva de SP", "sessao-1");

        assertThat(response.pendingAction()).isNotNull();
        assertThat(response.pendingAction().id()).isEqualTo("acao-1");
        assertThat(response.pendingAction().summary()).isEqualTo("Cadastrar um novo motorista");
        assertThat(response.pendingAction().arguments())
                .containsEntry("Nome", "João Silva")
                .containsEntry("Estado", "SP");
        assertThat(response.content())
                .startsWith("Vou cadastrar o motorista João Silva.")
                .contains("Nada foi gravado ainda");
    }

    @Test
    void pendingWriteNoticeContradictsClaimOfCompletion() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-2", "sessao-1", "createVehicle",
                    "{\"name\":\"Truck X\"}", Instant.now(), Map.of()));
            return "Veículo Truck X cadastrado com sucesso!";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck X", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado ainda");
    }

    @Test
    void readOnlyAnswerHasNoPendingAction() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 motoristas."));

        ChatMessageDTO response = chatService.respond("quantos motoristas existem?", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).isEqualTo("Há 42 motoristas.");
    }

    @Test
    void actionClaimWithoutPendingTriggersCorrectiveRetry() {
        whenLlmAnswers()
                .thenAnswer(counting(invocation ->
                        "A ação de cadastrar o motorista João Ribeiro será realizada. Aguardando sua confirmação."))
                .thenAnswer(counting(invocation -> {
                    pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                            "{\"name\":\"João Ribeiro\"}", Instant.now(), Map.of()));
                    return "Vou cadastrar o motorista João Ribeiro.";
                }));

        ChatMessageDTO response = chatService.respond("joao.ribeiro@teste.com, 03/08/2003, Palhoça, SC", "sessao-1");

        assertThat(response.pendingAction()).isNotNull();
        assertThat(llmCalls).hasValue(2);
    }

    @Test
    void actionClaimWithoutPendingIsContradictedAfterTwoRetries() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A ação será realizada. Aguardando sua confirmação."));

        ChatMessageDTO response = chatService.respond("cadastre o motorista João", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).contains("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(3);
    }

    @Test
    void askingForMissingDataIsNotAnActionClaim() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Para cadastrar João Ribeiro preciso do e-mail, da data de nascimento, da cidade e "
                        + "do estado. Por favor, forneça essas informações para que eu possa registrar a ação."));

        ChatMessageDTO response = chatService.respond("adicione um novo motorista João Ribeiro", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).doesNotContain("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(1);
    }

    @Test
    void actionClaimWithPendingIsLeftAlone() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                    "{\"name\":\"João\"}", Instant.now(), Map.of()));
            return "Aguardando sua confirmação.";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o motorista João", "sessao-1");

        assertThat(response.content()).doesNotContain("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(1);
    }
}
