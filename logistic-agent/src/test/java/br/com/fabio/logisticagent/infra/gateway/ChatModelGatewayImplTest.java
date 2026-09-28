package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.agent.QueryResultHolder;
import br.com.fabio.logisticagent.core.agent.RenderHolder;
import br.com.fabio.logisticagent.core.agent.tools.RenderTool;
import br.com.fabio.logisticagent.core.domain.exception.PermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelGatewayImplTest {

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatModelGatewayImpl gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(any(String.class)).user(any(String.class)).advisors(any(Consumer.class)))
                .thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        RenderHolder renderHolder = new RenderHolder();
        gateway = new ChatModelGatewayImpl(chatClient, new RenderTool(renderHolder, new QueryResultHolder()));
    }

    private ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
    }

    @Test
    void returnsModelContent() {
        when(requestSpec.call().content()).thenReturn("resposta");

        assertThat(gateway.ask("pergunta", "sessao-1")).isEqualTo("resposta");
    }

    @Test
    void permissionDeniedToolCallBecomesPermissionDeniedException() {
        ToolExecutionException denied = new ToolExecutionException(tool("deleteDriver"),
                new IllegalStateException("Error invoking method: deleteDriver\n"
                        + ChatModelGatewayImpl.PERMISSION_DENIED_MARKER + ": requer a role \"write\""));
        when(requestSpec.call().content()).thenThrow(denied);

        assertThatThrownBy(() -> gateway.ask("exclua o motorista X", "sessao-1"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasCause(denied);
    }

    @Test
    void toolExecutionExceptionWithoutMarkerPropagates() {
        ToolExecutionException other = new ToolExecutionException(tool("createOrder"),
                new IllegalStateException("algum outro erro de negócio"));
        when(requestSpec.call().content()).thenThrow(other);

        assertThatThrownBy(() -> gateway.ask("crie um pedido", "sessao-1")).isSameAs(other);
    }
}
