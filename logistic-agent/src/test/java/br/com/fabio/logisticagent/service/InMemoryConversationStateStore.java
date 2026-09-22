package br.com.fabio.logisticagent.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConversationStateStore implements IConversationStateStore {

    private final Map<String, Boolean> writeIntents = new ConcurrentHashMap<>();

    @Override
    public boolean hasWriteIntent(String conversationId) {
        return writeIntents.containsKey(conversationId);
    }

    @Override
    public void setWriteIntent(String conversationId, boolean requested) {
        set(writeIntents, conversationId, requested);
    }

    private void set(Map<String, Boolean> map, String conversationId, boolean value) {
        if (value) {
            map.put(conversationId, Boolean.TRUE);
        } else {
            map.remove(conversationId);
        }
    }
}
