package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.core.agent.PendingActionHolder;
import br.com.fabio.logisticagent.core.guardrail.WriteConfirmationGuardrail;
import br.com.fabio.logisticagent.infra.client.ConfirmingToolCallbackProvider;
import br.com.fabio.logisticagent.infra.gateway.ChatModelGatewayImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class ChatClientConfig {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    private static final Duration LLM_READ_TIMEOUT = Duration.ofSeconds(300);

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider mcpToolCallbacks,
            ChatMemory chatMemory, WriteConfirmationGuardrail writeConfirmation,
            ObjectProvider<PendingActionHolder> pendingActionHolder,
            @Value("${LLM_EXTRA_BODY:}") String llmExtraBody) {
        return builder
                .defaultTools(new ConfirmingToolCallbackProvider(
                        mcpToolCallbacks, writeConfirmation, pendingActionHolder))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(OpenAiChatOptions.builder().extraBody(parseExtraBody(llmExtraBody)))
                .build();
    }

    static Map<String, Object> parseExtraBody(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception e) {
            throw new IllegalStateException("LLM_EXTRA_BODY não é um JSON válido: " + json, e);
        }
    }

    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        ToolExecutionExceptionProcessor defaultProcessor = DefaultToolExecutionExceptionProcessor.builder().build();
        return exception -> {
            if (ChatModelGatewayImpl.isPermissionDenied(exception)) {
                throw exception;
            }
            return defaultProcessor.process(exception);
        };
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer llmTimeoutCustomizer() {
        Timeout timeout = Timeout.builder()
                .connect(Duration.ofSeconds(10))
                .read(LLM_READ_TIMEOUT)
                .build();
        return httpClientBuilder -> httpClientBuilder.timeout(timeout);
    }
}
