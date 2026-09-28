package br.com.fabio.logisticagent.core.gateway;

class InMemoryConversationStateGatewayTest extends ConversationStateGatewayContractTest {

    @Override
    protected ConversationStateGateway createStore() {
        return new InMemoryConversationStateGateway();
    }
}
