package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.confirm.IPendingActionRepository;
import br.com.fabio.logisticagent.confirm.IPendingActionStore;
import br.com.fabio.logisticagent.service.IConversationStateRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
public class AgentStatePurge {

    private static final Logger log = LoggerFactory.getLogger(AgentStatePurge.class);

    private static final Duration CONVERSATION_STATE_TTL = Duration.ofHours(24);

    private final IPendingActionRepository pendingActions;
    private final IConversationStateRepository conversationStates;
    private final EntityManager entityManager;

    @Value("${logistic.agent.state-purge.chat-memory-ttl:24h}")
    private Duration chatMemoryTtl;

    public AgentStatePurge(IPendingActionRepository pendingActions,
            IConversationStateRepository conversationStates, EntityManager entityManager) {
        this.pendingActions = pendingActions;
        this.conversationStates = conversationStates;
        this.entityManager = entityManager;
    }

    @Scheduled(fixedDelayString = "${logistic.agent.state-purge.interval:3600000}",
            initialDelayString = "${logistic.agent.state-purge.interval:3600000}")
    @Transactional
    public void purge() {
        Instant now = Instant.now();
        int chatMemoryRows = purgeChatMemory(now);
        int pendingActionRows = pendingActions.deleteExpired(now.minus(IPendingActionStore.TTL));
        int conversationStateRows = conversationStates.deleteStale(now.minus(CONVERSATION_STATE_TTL));

        if (chatMemoryRows > 0 || pendingActionRows > 0 || conversationStateRows > 0) {
            log.info("Purga de estado do agent: {} linha(s) de chat memory, {} pending_action, "
                            + "{} conversation_state",
                    chatMemoryRows, pendingActionRows, conversationStateRows);
        }
    }

    private int purgeChatMemory(Instant now) {
        return entityManager.createNativeQuery("""
                        DELETE FROM spring_ai_chat_memory
                        WHERE conversation_id IN (
                            SELECT conversation_id
                            FROM spring_ai_chat_memory
                            GROUP BY conversation_id
                            HAVING MAX("timestamp") < ?1
                        )
                        """)
                .setParameter(1, Timestamp.from(now.minus(chatMemoryTtl)))
                .executeUpdate();
    }
}
