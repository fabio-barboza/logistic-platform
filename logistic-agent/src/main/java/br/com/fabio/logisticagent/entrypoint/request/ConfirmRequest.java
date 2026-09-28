package br.com.fabio.logisticagent.entrypoint.request;

public record ConfirmRequest(String sessionId, String actionId, boolean approved) {
}
