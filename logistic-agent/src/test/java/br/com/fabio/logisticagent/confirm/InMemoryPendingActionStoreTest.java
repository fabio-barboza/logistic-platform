package br.com.fabio.logisticagent.confirm;

/** Roda o contrato de {@link PendingActionStoreContractTest} contra o dublê em memória. */
class InMemoryPendingActionStoreTest extends PendingActionStoreContractTest {

    @Override
    protected IPendingActionStore createStore() {
        return new InMemoryPendingActionStore();
    }
}
