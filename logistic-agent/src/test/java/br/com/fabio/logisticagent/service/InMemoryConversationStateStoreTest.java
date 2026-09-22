package br.com.fabio.logisticagent.service;

class InMemoryConversationStateStoreTest extends ConversationStateStoreContractTest {

    @Override
    protected IConversationStateStore createStore() {
        return new InMemoryConversationStateStore();
    }
}
