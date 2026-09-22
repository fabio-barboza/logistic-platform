package br.com.fabio.logisticagent.dto;

public record ConfirmRequestDTO(String sessionId, String actionId, boolean approved) {
}
