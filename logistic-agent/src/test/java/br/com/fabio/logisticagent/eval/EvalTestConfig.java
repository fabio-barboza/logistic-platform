package br.com.fabio.logisticagent.eval;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitui, só no eval, o ToolCallbackProvider injetado no ChatClient por um decorator
 * que registra as tools chamadas. O bean real do starter MCP ({@code mcpToolCallbacks})
 * continua no contexto e é usado como delegate.
 */
@TestConfiguration
public class EvalTestConfig {

    @Bean
    ToolCallRecorder toolCallRecorder() {
        return new ToolCallRecorder();
    }

    @Bean
    @Primary
    ToolCallbackProvider recordingToolCallbackProvider(
            @Qualifier("mcpToolCallbacks") ToolCallbackProvider mcpToolCallbacks,
            ToolCallRecorder recorder) {
        return new RecordingToolCallbackProvider(mcpToolCallbacks, recorder);
    }
}
