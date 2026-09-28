package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.McpToolGateway;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component
public class McpToolGatewayImpl implements McpToolGateway {

    private final ToolCallbackProvider mcpToolCallbacks;

    public McpToolGatewayImpl(ToolCallbackProvider mcpToolCallbacks) {
        this.mcpToolCallbacks = mcpToolCallbacks;
    }

    @Override
    public Optional<String> call(String toolName, String argsJson) {
        return resolve(toolName).map(callback -> callback.call(argsJson));
    }

    private Optional<ToolCallback> resolve(String toolName) {
        ToolCallback[] callbacks = mcpToolCallbacks.getToolCallbacks();
        return Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals(toolName))
                .findFirst()
                .or(() -> Arrays.stream(callbacks)
                        .filter(callback -> callback.getToolDefinition().name().endsWith("_" + toolName))
                        .findFirst());
    }
}
