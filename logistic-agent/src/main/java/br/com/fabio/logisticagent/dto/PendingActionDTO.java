package br.com.fabio.logisticagent.dto;

import java.util.Map;

public record PendingActionDTO(String id, String tool, String summary, Map<String, String> arguments,
                               boolean destructive) {
}
