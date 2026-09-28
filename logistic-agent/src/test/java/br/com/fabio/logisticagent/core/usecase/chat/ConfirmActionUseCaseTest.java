package br.com.fabio.logisticagent.core.usecase.chat;

import br.com.fabio.logisticagent.core.domain.chat.ChatMessage;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.gateway.ChatHistoryGateway;
import br.com.fabio.logisticagent.core.gateway.InMemoryPendingActionGateway;
import br.com.fabio.logisticagent.core.gateway.McpToolGateway;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmActionUseCaseTest {

    private final List<String> executed = new ArrayList<>();
    private final Map<String, List<String>> history = new HashMap<>();

    private PendingActionGateway store;
    private ConfirmActionUseCase confirmActionUseCase;

    private UnaryOperator<String> currentTool;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingActionGateway();
        McpToolGateway mcpToolGateway = (name, args) -> currentTool == null || !name.equals("createDriver")
                ? Optional.empty()
                : Optional.of(currentTool.apply(args));
        ChatHistoryGateway chatHistoryGateway = new ChatHistoryGateway() {

            @Override
            public void appendAssistant(String conversationId, String text) {
                history.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(text);
            }

            @Override
            public int deleteIdleSince(Instant limit) {
                return 0;
            }
        };
        confirmActionUseCase = new ConfirmActionUseCase(store, chatHistoryGateway, mcpToolGateway,
                JsonMapper.builder().build());
    }

    private PendingAction register(UnaryOperator<String> body) {
        return register("sessao-1", body);
    }

    private PendingAction register(String key, UnaryOperator<String> body) {
        currentTool = body;
        return store.register(key, "createDriver", "{\"name\":\"João\"}");
    }

    private String memory() {
        return String.join("", history.getOrDefault("sessao-1", List.of()));
    }

    @Test
    void approvedActionRunsTheOriginalPayload() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "{\"id\":\"abc\"}";
        });

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(executed).containsExactly("{\"name\":\"João\"}");
        assertThat(response.content()).contains("executada").contains("\"id\" : \"abc\"");
        assertThat(memory()).contains("CONFIRMOU").contains("abc");
    }

    @Test
    void rejectedActionIsNotExecutedAndLeavesATraceInMemory() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "ok";
        });

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), false);

        assertThat(executed).isEmpty();
        assertThat(response.content()).contains("cancelada");
        assertThat(memory()).contains("CANCELOU");
    }

    @Test
    void secondConfirmationOfTheSameActionDoesNothing() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "ok";
        });
        confirmActionUseCase.execute("sessao-1", action.id(), true);

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(executed).hasSize(1);
        assertThat(response.content()).contains("Não encontrei essa ação pendente");
    }

    @Test
    void toolFailureIsReportedAndRecordedAsNotWritten() {
        PendingAction action = register(input -> {
            throw new IllegalStateException("E-mail já cadastrado");
        });

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(response.content()).contains("Não foi possível executar").contains("E-mail já cadastrado");
        assertThat(memory()).contains("FALHOU").contains("Nada foi gravado");
    }

    @Test
    void mcpEnvelopeIsUnwrappedForTheUser() {
        PendingAction action = register(input ->
                "[{\"text\":\"{\\\"id\\\":\\\"abc\\\",\\\"name\\\":\\\"Diag\\\"}\"}]");

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(response.content())
                .contains("\"id\" : \"abc\"")
                .doesNotContain("\\\"");
    }

    @Test
    void pendingActionIsNotResolvableFromAnotherConversation() {
        PendingAction action = register("user-1|sessao-1", input -> {
            executed.add(input);
            return "{\"id\":\"abc\"}";
        });

        ChatMessage deniedResponse = confirmActionUseCase.execute("user-2|sessao-1", action.id(), true);

        assertThat(executed).isEmpty();
        assertThat(deniedResponse.content()).contains("Não encontrei essa ação pendente");

        ChatMessage okResponse = confirmActionUseCase.execute("user-1|sessao-1", action.id(), true);

        assertThat(executed).containsExactly("{\"name\":\"João\"}");
        assertThat(okResponse.content()).contains("executada");
    }

    @Test
    void unresolvableToolReportsBackendUnavailableInsteadOfActionNotFound() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "ok";
        });
        currentTool = null;

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(executed).isEmpty();
        assertThat(response.content()).contains("indisponível");
        assertThat(memory()).contains("indisponível");
    }

    @Test
    void plainTextResultIsKeptAsIs() {
        PendingAction action = register(input -> "Motorista X vinculado ao veículo Y com sucesso.");

        ChatMessage response = confirmActionUseCase.execute("sessao-1", action.id(), true);

        assertThat(response.content()).contains("vinculado ao veículo Y com sucesso");
    }
}
