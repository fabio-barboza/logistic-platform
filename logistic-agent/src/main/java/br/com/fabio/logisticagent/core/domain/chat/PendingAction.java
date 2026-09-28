package br.com.fabio.logisticagent.core.domain.chat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PendingAction(String id, String sessionId, String toolName, String argsJson, Instant createdAt,
                            Map<String, String> details) {

    public PendingAction {
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public boolean matches(String otherTool, String otherArgs) {
        return toolName.equals(otherTool) && argsJson.equals(otherArgs == null ? "" : otherArgs);
    }
}
