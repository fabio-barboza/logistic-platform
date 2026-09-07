package br.com.fabio.logisticagent.service;

/** Roda o contrato de {@link ConversationStateStoreContractTest} contra o dublê em memória. */
class InMemoryConversationStateStoreTest extends ConversationStateStoreContractTest {

    @Override
    protected IConversationStateStore createStore() {
        return new InMemoryConversationStateStore();
    }
}
