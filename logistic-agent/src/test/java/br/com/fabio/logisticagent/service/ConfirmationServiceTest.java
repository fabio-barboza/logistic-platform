package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.InMemoryPendingActionStore;
import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.IPendingActionStore;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ConfirmRequestDTO;
import br.com.fabio.logisticagent.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationServiceTest {

    private final List<String> executed = new ArrayList<>();

    private IPendingActionStore store;
    private ChatMemory chatMemory;
    private ConfirmationService confirmationService;

    /**
     * A tool "createDriver" que o {@code ToolCallbackProvider} abaixo devolve. Mutável porque cada
     * teste chama {@link #register} com um comportamento diferente, e o provider é lido de novo a
     * cada {@code resolve()} — o mesmo padrão real de {@code ConfirmationService}, que resolve o
     * callback pelo nome só na hora de confirmar, nunca guardado no {@code PendingAction}.
     */
    private ToolCallback currentCallback;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingActionStore();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        ToolCallbackProvider mcpToolCallbacks = () -> currentCallback == null
                ? new ToolCallback[0]
                : new ToolCallback[] {currentCallback};
        confirmationService = new ConfirmationService(store, chatMemory, JsonMapper.builder().build(),
                mcpToolCallbacks);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    /** Tool falsa no lugar da chamada MCP: o que importa aqui é o payload que chega nela. */
    private PendingAction register(UnaryOperator<String> body) {
        return register("sessao-1", body);
    }

    private PendingAction register(String key, UnaryOperator<String> body) {
        currentCallback = new ToolCallback() {

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
        return store.register(key, "createDriver", "{\"name\":\"João\"}");
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

    /**
     * A pendência é resgatada pela chave de conversa (sub + sessionId), não pelo
     * sessionId cru — resolve() computa essa chave a partir do usuário autenticado na requisição
     * de confirmação. Sem isso, o mesmo sessionId (sessionStorage forçado, ou coincidência)
     * resgataria a pendência de outro usuário.
     */
    @Test
    void pendingActionIsNotResolvableByAnotherUserWithTheSameSessionId() {
        authenticateAs("user-1");
        PendingAction action = register(AuthenticatedUser.conversationId("sessao-1"),
                input -> {
                    executed.add(input);
                    return "{\"id\":\"abc\"}";
                });

        authenticateAs("user-2");
        ChatMessageDTO deniedResponse = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(executed).isEmpty();
        assertThat(deniedResponse.content()).contains("Não encontrei essa ação pendente");

        authenticateAs("user-1");
        ChatMessageDTO okResponse = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(executed).containsExactly("{\"name\":\"João\"}");
        assertThat(okResponse.content()).contains("executada");
    }

    /**
     * Quem recebe o clique pode não ter a tool no handshake MCP do próprio startup (API fora do ar
     * quando ele subiu). A mensagem tem que dizer "indisponível", e não "ação não encontrada": são
     * causas diferentes e o usuário reage diferente a cada uma.
     */
    @Test
    void unresolvableToolReportsBackendUnavailableInsteadOfActionNotFound() {
        PendingAction action = register(input -> {
            executed.add(input);
            return "ok";
        });
        currentCallback = null; // createDriver não está na lista de tools desta instância

        ChatMessageDTO response = confirmationService.resolve(
                new ConfirmRequestDTO("sessao-1", action.id(), true));

        assertThat(executed).isEmpty();
        assertThat(response.content()).contains("indisponível");
        assertThat(memory()).contains("indisponível");
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
