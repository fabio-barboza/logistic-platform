package br.com.fabio.logisticagent.core.gateway;

class InMemoryPendingActionGatewayTest extends PendingActionGatewayContractTest {

    @Override
    protected PendingActionGateway createStore() {
        return new InMemoryPendingActionGateway();
    }
}
