package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionStore;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ConfirmRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationServiceTest {

    private final List<String> executed = new ArrayList<>();

    private PendingActionStore store;
    private ChatMemory chatMemory;
    private ConfirmationService confirmationService;

    @BeforeEach
    void setUp() {
        store = new PendingActionStore();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        confirmationService = new ConfirmationService(store, chatMemory, JsonMapper.builder().build());
    }

    /** Tool falsa no lugar da chamada MCP: o que importa aqui é o payload que chega nela. */
    private PendingAction register(UnaryOperator<String> body) {
        ToolCallback callback = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("createDriver").description("createDriver").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
        return store.register("sessao-1", "createDriver", "{\"name\":\"João\"}", callback);
    }

    private String memory() {
        return chatMemory.get("sessao-1").stream().map(Message::getText).reduce("", String::concat);
    }

    @Test
    void approvedActionRunsTheOriginalPayload() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "{\"id\":\"abc\"}";
        });

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

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

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), false));

        assertThat(executed).isEmpty();
        assertThat(response.content()).contains("cancelada");
        assertThat(memory()).contains("CANCELOU");
    }

    /** Segundo clique (ou pendência expirada) não pode executar de novo. */
    @Test
    void secondConfirmationOfTheSameActionDoesNothing() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "ok";
        });
        confirmationService.resolve(new ConfirmRequestDTO("sessao-1", action.id(), true));

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(executed).hasSize(1);
        assertThat(response.content()).contains("Não encontrei essa ação pendente");
    }

    @Test
    void toolFailureIsReportedAndRecordedAsNotWritten() {
        PendingAction action = register(input -> {
            throw new IllegalStateException("E-mail já cadastrado");
        });

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(response.content()).contains("Não foi possível executar").contains("E-mail já cadastrado");
        assertThat(memory()).contains("FALHOU").contains("Nada foi gravado");
    }

    /** O MCP devolve o registro dentro de uma string escapada; a tela não pode mostrar isso cru. */
    @Test
    void mcpEnvelopeIsUnwrappedForTheUser() {
        PendingAction action = register(input ->
                "[{\"text\":\"{\\\"id\\\":\\\"abc\\\",\\\"name\\\":\\\"Diag\\\"}\"}]");

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(response.content())
                .contains("\"id\" : \"abc\"")
                .doesNotContain("\\\"");
    }

    /** Retorno que não é JSON (as tools de vínculo devolvem frase) passa intacto. */
    @Test
    void plainTextResultIsKeptAsIs() {
        PendingAction action = register(input -> "Motorista X vinculado ao veículo Y com sucesso.");

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(response.content()).contains("vinculado ao veículo Y com sucesso");
    }
}
