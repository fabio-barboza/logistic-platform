package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionMapper;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.tool.RenderHolder;
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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        renderHolder = new RenderHolder();
        toolCallHolder = new ToolCallHolder();
        pendingActionHolder = new PendingActionHolder();
        // Caso normal: o modelo consultou o banco antes de responder. Os testes de render partem
        // daí, senão cada resposta com número cairia também no retry de dado sem tool.
        toolCallHolder.register("executeQuery");
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        chatService = new ChatService(chatClient, renderHolder, toolCallHolder, pendingActionHolder,
                new PendingActionMapper(JsonMapper.builder().build()), tracerProvider);
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

    /**
     * A tool MCP recusada por falta de permissão chega ao agent como {@link ToolExecutionException}
     * cuja causa carrega o marcador {@code insufficient_scope} (ver ChatClientConfig e
     * McpAuthorizationException no logistic-api) — ChatService precisa reconhecer isso e responder
     * com uma mensagem amigável, em vez de deixar a exceção estourar para o controller.
     */
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

    /**
     * A chave da oferta pendente é a conversa (sub + sessionId), não o sessionId
     * cru. Sem isso, o "sim" de user-b resolveria a oferta feita a user-a só porque as duas abas
     * mandam o mesmo sessionId (ex.: sessionStorage forçado, ou coincidência).
     */
    @Test
    void pendingVisualOfferIsIsolatedByAuthenticatedUser() {
        authenticateAs("user-a");
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A taxa de falha em SP é 18,8%. Posso mostrar isso em gráfico, se quiser."));
        chatService.respond("qual a taxa de falha por estado?", "sessao-1");

        authenticateAs("user-b");
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

    /**
     * Duas correções e para: o loop de tool calls não pode ficar preso no modelo teimoso. E o que
     * sobra na tela não pode ser só o número inventado — quem insistiu foi o modelo, quem paga a
     * conta é quem lê.
     */
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

    /**
     * Falha real, com o usuário sem a role {@code write}: sem as tools de escrita na lista, o
     * modelo tenta contornar por executeQuery (o INSERT morre na role read-only) e anuncia
     * "cadastrado com sucesso". Nada foi gravado — a fronteira é a role na API —, mas a tela dizia
     * o contrário. Repare que houve tool call no turno: o gatilho aqui não pode ser "nenhuma tool
     * chamada", e sim o invariante de que escrita nunca conclui dentro de um turno do chat.
     */
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

    /** Primeira pessoa é a mesma afirmação com outra roupa: "cadastrei o veículo". */
    @Test
    void firstPersonWriteClaimWithoutPendingIsContradicted() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Pronto, cadastrei o veículo Truck Y."));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck Y", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado");
    }

    /**
     * {@code created_at} lido do banco não é anúncio de escrita: "foi cadastrado em 12/03/2024" é
     * resposta de leitura legítima, e desmentir aí seria mentir para o usuário.
     */
    @Test
    void readingCreationDateIsNotAWriteClaim() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "O motorista João Silva foi cadastrado em 12/03/2024 e atende a região de Campinas."));

        ChatMessageDTO response = chatService.respond("quando o João Silva entrou?", "sessao-1");

        assertThat(response.content()).doesNotContain("Nada foi gravado");
        assertThat(llmCalls).hasValue(1);
    }

    /**
     * O caso que motivou o gatilho por pedido do usuário: a mesma pergunta, feita três vezes por um
     * usuário sem {@code write}, rendeu três frases diferentes ("cadastrado com sucesso", "a ação
     * foi registrada", "será cadastrado assim que você confirmar na tela"). Perseguir a frase do
     * modelo é corrida perdida — aqui o gatilho é o pedido do usuário mais a ausência de pendência.
     */
    @Test
    void writeRequestThatRegisteredNothingIsAnnouncedAsNotWritten() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "O veículo Truck Z com capacidade de 200 kg está pronto para entrar na frota."));

        ChatMessageDTO response = chatService.respond(
                "cadastre um veículo chamado Truck Z com capacidade 200", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).contains("Nada foi gravado nesta resposta");
    }

    /** O aceite não repete o verbo: "sim" depois do pedido de escrita continua sendo escrita. */
    @Test
    void bareYesAfterAWriteRequestIsStillAWriteTurn() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Confirma que a capacidade é 200 kg?"));
        chatService.respond("cadastre um veículo chamado Truck Z com capacidade 200", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> "Pronto! O veículo Truck Z já está na frota."));
        ChatMessageDTO response = chatService.respond("sim", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado nesta resposta");
    }

    /** Pergunta do modelo mantém o fluxo aberto: ninguém foi informado de que algo aconteceu. */
    @Test
    void questionBackToTheUserIsNotAnnouncedAsNotWritten() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Para cadastrar preciso do e-mail e da data de nascimento. Pode me informar?"));

        ChatMessageDTO response = chatService.respond("cadastre um motorista chamado João", "sessao-1");

        assertThat(response.content()).doesNotContain("Nada foi gravado");
    }

    /**
     * Recusa correta não precisa de aviso: a frase já disse que não deu. As duas formas abaixo
     * saíram do modelo em execuções reais da mesma pergunta ("apague o pedido mais antigo").
     */
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

    /**
     * A supressão da recusa não pode virar esconderijo: quando a mesma resposta recusa uma coisa e
     * afirma outra como feita, a afirmação explícita tem precedência e é desmentida.
     */
    @Test
    void denialMixedWithAWriteClaimIsStillContradicted() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Não posso excluir pedidos, mas cadastrei o veículo Truck Z para você."));

        ChatMessageDTO response = chatService.respond("apague o pedido e cadastre o veículo Truck Z", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado");
    }

    /** O pedido de escrita não pode sobreviver ao assunto seguinte. */
    @Test
    void writeIntentDoesNotLeakIntoTheNextQuestion() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Confirma a capacidade?"));
        chatService.respond("cadastre um veículo Truck Z", "sessao-1");

        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 motoristas."));
        ChatMessageDTO response = chatService.respond("quantos motoristas existem?", "sessao-1");

        assertThat(response.content()).isEqualTo("Há 42 motoristas.");
    }

    /** Escrita registrada de verdade: a frase de conclusão é desmentida pelo aviso de pendência. */
    @Test
    void writeSuccessClaimWithPendingKeepsOnlyThePendingNotice() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-3", "sessao-1", "createVehicle",
                    "{\"name\":\"Truck Y\"}", null, Instant.now(), Map.of()));
            return "Veículo Truck Y cadastrado com sucesso.";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck Y", "sessao-1");

        assertThat(response.content())
                .contains("Nada foi gravado ainda")
                .doesNotContain("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(1);
    }

    /**
     * Perguntar de volta é a resposta certa quando falta dado, e a pergunta costuma repetir o
     * número que o usuário deu ("...capacidade de 200 kg?"). Desmentir aí seria ruído.
     */
    @Test
    void questionRepeatingUserNumbersIsNotContradicted() {
        toolCallHolder.reset();
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "Deseja cadastrar um novo veículo com esse nome e capacidade de 200 kg?"));

        ChatMessageDTO response = chatService.respond("cadastre um veículo Truck Y com capacidade 200", "sessao-1");

        assertThat(response.content()).doesNotContain("Nenhuma consulta e nenhuma gravação aconteceram");
    }

    /**
     * Quando as duas heurísticas casam na mesma resposta, vale só a mais específica: o usuário
     * está esperando um botão. Dois avisos de "isso não aconteceu" viram ruído.
     */
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

    /** Escrita registrada: a tela recebe a pendência e o texto avisa que nada foi gravado. */
    @Test
    void pendingWriteIsReturnedWithNotice() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                    "{\"name\":\"João Silva\",\"state\":\"SP\"}", null, Instant.now(), Map.of()));
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

    /**
     * O aviso é incondicional: mesmo quando o modelo afirma ter cadastrado, a tela não pode
     * ficar só com essa frase — é a afirmação falsa que a confirmação existe para impedir.
     */
    @Test
    void pendingWriteNoticeContradictsClaimOfCompletion() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-2", "sessao-1", "createVehicle",
                    "{\"name\":\"Truck X\"}", null, Instant.now(), Map.of()));
            return "Veículo Truck X cadastrado com sucesso!";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o veículo Truck X", "sessao-1");

        assertThat(response.content()).contains("Nada foi gravado ainda");
    }

    /** Sem escrita, nenhum aviso e nenhuma pendência na resposta. */
    @Test
    void readOnlyAnswerHasNoPendingAction() {
        whenLlmAnswers().thenAnswer(counting(invocation -> "Há 42 motoristas."));

        ChatMessageDTO response = chatService.respond("quantos motoristas existem?", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).isEqualTo("Há 42 motoristas.");
    }

    /**
     * O modelo anuncia a confirmação sem chamar tool nenhuma — a tela ficaria com a frase e sem
     * botão. Falha real: "Adicione um novo motorista João Ribeiro" + dados, log de tool calls vazio.
     */
    @Test
    void actionClaimWithoutPendingTriggersCorrectiveRetry() {
        whenLlmAnswers()
                .thenAnswer(counting(invocation ->
                        "A ação de cadastrar o motorista João Ribeiro será realizada. Aguardando sua confirmação."))
                .thenAnswer(counting(invocation -> {
                    pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                            "{\"name\":\"João Ribeiro\"}", null, Instant.now(), Map.of()));
                    return "Vou cadastrar o motorista João Ribeiro.";
                }));

        ChatMessageDTO response = chatService.respond("joao.ribeiro@teste.com, 03/08/2003, Palhoça, SC", "sessao-1");

        assertThat(response.pendingAction()).isNotNull();
        assertThat(llmCalls).hasValue(2);
    }

    /** Insistiu duas vezes sem registrar: a tela precisa desmentir a promessa. */
    @Test
    void actionClaimWithoutPendingIsContradictedAfterTwoRetries() {
        whenLlmAnswers().thenAnswer(counting(invocation ->
                "A ação será realizada. Aguardando sua confirmação."));

        ChatMessageDTO response = chatService.respond("cadastre o motorista João", "sessao-1");

        assertThat(response.pendingAction()).isNull();
        assertThat(response.content()).contains("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(3);
    }

    /**
     * Pedir os dados que faltam é a resposta certa quando a tool recusou por campo obrigatório —
     * não pode virar retry só porque a frase menciona registrar a ação.
     */
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

    /** Com pendência registrada, a frase de confirmação é verdadeira e nada é desmentido. */
    @Test
    void actionClaimWithPendingIsLeftAlone() {
        whenLlmAnswers().thenAnswer(counting(invocation -> {
            pendingActionHolder.set(new PendingAction("acao-1", "sessao-1", "createDriver",
                    "{\"name\":\"João\"}", null, Instant.now(), Map.of()));
            return "Aguardando sua confirmação.";
        }));

        ChatMessageDTO response = chatService.respond("cadastre o motorista João", "sessao-1");

        assertThat(response.content()).doesNotContain("Nenhuma ação foi registrada");
        assertThat(llmCalls).hasValue(1);
    }
}
