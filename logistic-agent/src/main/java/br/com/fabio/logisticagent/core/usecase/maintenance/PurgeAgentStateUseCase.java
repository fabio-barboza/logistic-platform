package br.com.fabio.logisticagent.core.usecase.maintenance;

import br.com.fabio.logisticagent.core.gateway.ChatHistoryGateway;
import br.com.fabio.logisticagent.core.gateway.ConversationStateGateway;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import br.com.fabio.logisticagent.core.settings.StatePurgeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class PurgeAgentStateUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurgeAgentStateUseCase.class);

    private static final Duration CONVERSATION_STATE_TTL = Duration.ofHours(24);

    private final ChatHistoryGateway chatHistoryGateway;
    private final PendingActionGateway pendingActionGateway;
    private final ConversationStateGateway conversationStateGateway;
    private final Duration chatMemoryTtl;

    public PurgeAgentStateUseCase(ChatHistoryGateway chatHistoryGateway, PendingActionGateway pendingActionGateway,
            ConversationStateGateway conversationStateGateway, StatePurgeSettings settings) {
        this.chatHistoryGateway = chatHistoryGateway;
        this.pendingActionGateway = pendingActionGateway;
        this.conversationStateGateway = conversationStateGateway;
        this.chatMemoryTtl = settings.chatMemoryTtl();
    }

    @Transactional
    public void execute() {
        Instant now = Instant.now();
        int chatMemoryRows = chatHistoryGateway.deleteIdleSince(now.minus(chatMemoryTtl));
        int pendingActionRows = pendingActionGateway.deleteCreatedBefore(now.minus(PendingActionGateway.TTL));
        int conversationStateRows = conversationStateGateway.deleteUpdatedBefore(now.minus(CONVERSATION_STATE_TTL));

        if (chatMemoryRows > 0 || pendingActionRows > 0 || conversationStateRows > 0) {
            log.info("Purga de estado do agent: {} linha(s) de chat memory, {} pending_action, "
                            + "{} conversation_state",
                    chatMemoryRows, pendingActionRows, conversationStateRows);
        }
    }
}
