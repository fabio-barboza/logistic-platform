package br.com.fabio.logisticagent.core.gateway;

public interface ChatModelGateway {

    String ask(String message, String conversationId);
}
