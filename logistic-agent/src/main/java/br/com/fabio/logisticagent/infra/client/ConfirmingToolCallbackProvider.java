package br.com.fabio.logisticagent.infra.client;

import br.com.fabio.logisticagent.core.agent.PendingActionHolder;
import br.com.fabio.logisticagent.core.guardrail.WriteConfirmationGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;

public class ConfirmingToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfirmingToolCallbackProvider.class);

    private final ToolCallbackProvider delegate;
    private final WriteConfirmationGuardrail guardrail;
    private final ObjectProvider<PendingActionHolder> holderProvider;

    public ConfirmingToolCallbackProvider(ToolCallbackProvider delegate, WriteConfirmationGuardrail guardrail,
            ObjectProvider<PendingActionHolder> holderProvider) {
        this.delegate = delegate;
        this.guardrail = guardrail;
        this.holderProvider = holderProvider;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .filter(this::allowed)
                .map(callback -> WriteConfirmationGuardrail.isWrite(callback.getToolDefinition().name())
                        ? (ToolCallback) new ConfirmingToolCallback(callback)
                        : callback)
                .toArray(ToolCallback[]::new);
    }

    private boolean allowed(ToolCallback callback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken)) {
            return true;
        }
        String role = WriteConfirmationGuardrail.requiredRole(callback.getToolDefinition().name());
        return auth.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    private class ConfirmingToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private ConfirmingToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

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
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String toolName = getToolDefinition().name();
            PendingActionHolder holder = holder();
            if (holder == null) {

                log.warn("Sem PendingActionHolder: {} executada sem confirmação (fora de requisição)", toolName);
                return delegate.call(toolInput, toolContext);
            }
            return guardrail.intercept(holder, toolName, getToolDefinition().inputSchema(), toolInput);
        }
    }

    private PendingActionHolder holder() {
        try {
            PendingActionHolder holder = holderProvider.getIfAvailable();
            if (holder != null) {
                holder.rejections();
            }
            return holder;
        } catch (RuntimeException e) {
            log.debug("Sem PendingActionHolder nesta execução: {}", e.getMessage());
            return null;
        }
    }
}
