package br.com.fabio.logisticagent.core.gateway;

import java.time.Instant;

public interface ConversationStateGateway {

    boolean hasWriteIntent(String conversationId);

    void setWriteIntent(String conversationId, boolean requested);

    int deleteUpdatedBefore(Instant limit);
}
