package br.com.fabio.logisticagent.confirm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

abstract class PendingActionStoreContractTest {

    private IPendingActionStore store;

    protected abstract IPendingActionStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }

    @Test
    void takeReturnsTheRegisteredAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{\"name\":\"João\"}");

        PendingAction taken = store.take(action.id(), "sessao-1");

        assertThat(taken).isNotNull();
        assertThat(taken.toolName()).isEqualTo("createDriver");
        assertThat(taken.argsJson()).isEqualTo("{\"name\":\"João\"}");
    }

    @Test
    void takeConsumesTheAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{}");

        assertThat(store.take(action.id(), "sessao-1")).isNotNull();
        assertThat(store.take(action.id(), "sessao-1")).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void takeFromAnotherSessionIsRefusedAndKeepsTheAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{}");

        assertThat(store.take(action.id(), "sessao-2")).isNull();
        assertThat(store.take(action.id(), "sessao-1")).isNotNull();
    }

    @Test
    void unknownIdReturnsNull() {
        assertThat(store.take("nao-existe", "sessao-1")).isNull();
    }

    @Test
    void detailsOrderSurvivesTheRoundTrip() {
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("Nome", "João Ribeiro");
        details.put("Cidade", "Recife");
        details.put("Estado", "PE");
        PendingAction action = store.register("sessao-1", "deleteDriver", "{\"id\":\"x\"}", details);

        PendingAction taken = store.take(action.id(), "sessao-1");

        assertThat(taken.details()).containsExactly(
                org.assertj.core.api.Assertions.entry("Nome", "João Ribeiro"),
                org.assertj.core.api.Assertions.entry("Cidade", "Recife"),
                org.assertj.core.api.Assertions.entry("Estado", "PE"));
    }
}
