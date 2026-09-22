package br.com.fabio.logisticagent.confirm;

import java.time.Duration;
import java.util.Map;

public interface IPendingActionStore {

    Duration TTL = Duration.ofMinutes(15);

    default PendingAction register(String sessionId, String toolName, String argsJson) {
        return register(sessionId, toolName, argsJson, Map.of());
    }

    PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details);

    PendingAction take(String id, String sessionId);

    int size();
}
