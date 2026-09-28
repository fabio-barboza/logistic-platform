package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.agent.tools.RenderTool;
import br.com.fabio.logisticagent.core.domain.exception.PermissionDeniedException;
import br.com.fabio.logisticagent.core.gateway.ChatModelGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Component
public class ChatModelGatewayImpl implements ChatModelGateway {

    public static final String PERMISSION_DENIED_MARKER = "insufficient_scope";

    private static final String SYSTEM_PROMPT_FILE = "prompts/system_prompt.md";

    private final ChatClient chatClient;
    private final RenderTool renderTool;
    private final String systemPrompt;

    public ChatModelGatewayImpl(ChatClient chatClient, RenderTool renderTool) {
        this.chatClient = chatClient;
        this.renderTool = renderTool;
        this.systemPrompt = load(SYSTEM_PROMPT_FILE);
    }

    @Override
    public String ask(String message, String conversationId) {
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .tools(renderTool)
                    .call()
                    .content();
        } catch (ToolExecutionException e) {
            if (isPermissionDenied(e)) {
                throw new PermissionDeniedException(e);
            }
            throw e;
        }
    }

    private static String load(String file) {
        try {
            return new ClassPathResource(file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + file, e);
        }
    }

    public static boolean isPermissionDenied(ToolExecutionException e) {
        Throwable cause = e.getCause();
        String message = cause != null ? cause.getMessage() : null;
        return message != null && message.contains(PERMISSION_DENIED_MARKER);
    }
}
