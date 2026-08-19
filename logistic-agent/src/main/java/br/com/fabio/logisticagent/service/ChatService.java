package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.tool.RenderHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final RenderHolder renderHolder;
    private final ObjectProvider<Tracer> tracerProvider;

    public ChatService(ChatClient chatClient, RenderHolder renderHolder, ObjectProvider<Tracer> tracerProvider) {
        this.chatClient = chatClient;
        this.renderHolder = renderHolder;
        this.tracerProvider = tracerProvider;
    }

    public ChatMessageDTO respond(String userMessage, String sessionId) {
        Span span = currentSpan();
        tagRequest(span, userMessage, sessionId);

        String content = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        tag(span, "langfuse.trace.output", content);

        RenderableContent renderData = renderHolder.get();
        return new ChatMessageDTO("assistant", content, renderData);
    }

    /** Span raiz da requisição, ou null quando o tracing está desligado. */
    private Span currentSpan() {
        Tracer tracer = tracerProvider.getIfAvailable();
        return tracer != null ? tracer.currentSpan() : null;
    }

    /**
     * Atributos que o Langfuse usa para nomear o trace, agrupá-lo por conversa e exibir
     * a pergunta do usuário no nível do trace.
     */
    private void tagRequest(Span span, String userMessage, String sessionId) {
        tag(span, "langfuse.trace.name", "chat");
        tag(span, "langfuse.session.id", sessionId);
        tag(span, "session.id", sessionId);
        tag(span, "langfuse.trace.input", userMessage);
    }

    private void tag(Span span, String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }
}
