package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.ChatHistoryGateway;
import jakarta.persistence.EntityManager;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class ChatHistoryGatewayImpl implements ChatHistoryGateway {

    private final ChatMemory chatMemory;
    private final EntityManager entityManager;

    public ChatHistoryGatewayImpl(ChatMemory chatMemory, EntityManager entityManager) {
        this.chatMemory = chatMemory;
        this.entityManager = entityManager;
    }

    @Override
    public void appendAssistant(String conversationId, String text) {
        chatMemory.add(conversationId, new AssistantMessage(text));
    }

    @Override
    @Transactional
    public int deleteIdleSince(Instant limit) {
        return entityManager.createNativeQuery("""
                        DELETE FROM spring_ai_chat_memory
                        WHERE conversation_id IN (
                            SELECT conversation_id
                            FROM spring_ai_chat_memory
                            GROUP BY conversation_id
                            HAVING MAX("timestamp") < ?1
                        )
                        """)
                .setParameter(1, Timestamp.from(limit))
                .executeUpdate();
    }
}
