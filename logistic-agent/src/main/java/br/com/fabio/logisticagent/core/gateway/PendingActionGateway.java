package br.com.fabio.logisticagent.core.gateway;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface PendingActionGateway {

    Duration TTL = Duration.ofMinutes(15);

    default PendingAction register(String sessionId, String toolName, String argsJson) {
        return register(sessionId, toolName, argsJson, Map.of());
    }

    PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details);

    PendingAction take(String id, String sessionId);

    int size();

    int deleteCreatedBefore(Instant limit);
}
