package br.com.fabio.logisticagent.eval;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;

/**
 * Decora o {@code ToolCallbackProvider} real (as tools descobertas por MCP na logistic-api),
 * registrando o nome de cada tool efetivamente chamada pelo modelo antes de delegar.
 *
 * <p>Existe só nos testes: o código de produção continua recebendo um ToolCallbackProvider
 * qualquer e não sabe que está sendo observado.
 */
class RecordingToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final ToolCallRecorder recorder;

    RecordingToolCallbackProvider(ToolCallbackProvider delegate, ToolCallRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> (ToolCallback) new RecordingToolCallback(callback, recorder))
                .toArray(ToolCallback[]::new);
    }

    private record RecordingToolCallback(ToolCallback delegate, ToolCallRecorder recorder) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            recorder.record(getToolDefinition().name());
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            recorder.record(getToolDefinition().name());
            return delegate.call(toolInput, toolContext);
        }
    }
}
