package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.tool.RenderHolder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final RenderHolder renderHolder;

    public ChatService(ChatClient chatClient, RenderHolder renderHolder) {
        this.chatClient = chatClient;
        this.renderHolder = renderHolder;
    }

    public ChatMessageDTO respond(String userMessage, String sessionId) {
        String content = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        RenderableContent renderData = renderHolder.get();
        return new ChatMessageDTO("assistant", content, renderData);
    }
}
