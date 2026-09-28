package br.com.fabio.logisticagent.entrypoint.response;

import java.util.Map;

public record PendingActionResponse(String id, String tool, String summary, Map<String, String> arguments,
                                    boolean destructive) {
}
