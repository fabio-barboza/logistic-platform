package br.com.fabio.logisticagent.service;

public interface IConversationStateStore {

    boolean hasWriteIntent(String conversationId);

    void setWriteIntent(String conversationId, boolean requested);
}
