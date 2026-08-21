package br.com.fabio.logisticagent.confirm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PendingActionStoreTest {

    private final PendingActionStore store = new PendingActionStore();
    private final ToolCallback callback = mock(ToolCallback.class);

    @Test
    void takeReturnsTheRegisteredAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{\"name\":\"João\"}", callback);

        PendingAction taken = store.take(action.id(), "sessao-1");

        assertThat(taken).isNotNull();
        assertThat(taken.argsJson()).isEqualTo("{\"name\":\"João\"}");
        assertThat(taken.callback()).isSameAs(callback);
    }

    /** Consumo único: dois cliques no botão gravariam duas vezes, e nenhuma escrita é idempotente. */
    @Test
    void takeConsumesTheAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{}", callback);

        assertThat(store.take(action.id(), "sessao-1")).isNotNull();
        assertThat(store.take(action.id(), "sessao-1")).isNull();
        assertThat(store.size()).isZero();
    }

    /** Id sozinho não basta: sem o par (id, sessionId) uma sessão confirmaria a ação de outra. */
    @Test
    void takeFromAnotherSessionIsRefusedAndKeepsTheAction() {
        PendingAction action = store.register("sessao-1", "createDriver", "{}", callback);

        assertThat(store.take(action.id(), "sessao-2")).isNull();
        assertThat(store.take(action.id(), "sessao-1")).isNotNull();
    }

    @Test
    void unknownIdReturnsNull() {
        assertThat(store.take("nao-existe", "sessao-1")).isNull();
    }

    @Test
    void capDropsTheOldestPendingActions() {
        for (int i = 0; i < PendingActionStore.MAX_PENDING + 10; i++) {
            store.register("sessao-" + i, "createDriver", "{}", callback);
        }

        assertThat(store.size()).isLessThanOrEqualTo(PendingActionStore.MAX_PENDING);
    }
}
