package br.com.fabio.logisticagent.core.gateway;

import java.time.Instant;

public interface ChatHistoryGateway {

    void appendAssistant(String conversationId, String text);

    int deleteIdleSince(Instant limit);
}
