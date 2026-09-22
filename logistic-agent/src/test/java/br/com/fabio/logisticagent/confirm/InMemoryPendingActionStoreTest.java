package br.com.fabio.logisticagent.confirm;

class InMemoryPendingActionStoreTest extends PendingActionStoreContractTest {

    @Override
    protected IPendingActionStore createStore() {
        return new InMemoryPendingActionStore();
    }
}
