package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code DefaultToolExecutionExceptionProcessor} do Spring AI não deixa uma
 * {@link ToolExecutionException} encerrar a chamada por padrão (converte em texto de volta ao
 * modelo) — o processor customizado precisa abrir exceção só para a recusa de permissão.
 */
class ChatClientConfigTest {

    private final ToolExecutionExceptionProcessor processor = new ChatClientConfig().toolExecutionExceptionProcessor();

    private ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
    }

    @Test
    void rethrowsWhenCauseCarriesPermissionDeniedMarker() {
        ToolExecutionException denied = new ToolExecutionException(tool("deleteDriver"),
                new IllegalStateException("Error invoking method: deleteDriver\n"
                        + ChatClientConfig.PERMISSION_DENIED_MARKER + ": requer a role \"write\""));

        assertThatThrownBy(() -> processor.process(denied)).isSameAs(denied);
    }

    @Test
    void returnsTextForOrdinaryToolFailures() {
        ToolExecutionException businessError = new ToolExecutionException(tool("deleteDriver"),
                new IllegalStateException("motorista possui 3 rota(s) e não pode ser excluído"));

        String result = processor.process(businessError);

        assertThat(result).contains("motorista possui 3 rota(s)");
    }
}
