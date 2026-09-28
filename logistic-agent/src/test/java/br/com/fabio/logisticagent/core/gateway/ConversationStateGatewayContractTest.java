package br.com.fabio.logisticagent.core.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class ConversationStateGatewayContractTest {

    private static final String CONVERSATION_ID = "user-sub|sessao-1";

    private ConversationStateGateway store;

    protected abstract ConversationStateGateway createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }

    @Test
    void hasWriteIntentIsFalseWhenNothingWasSet() {
        assertThat(store.hasWriteIntent(CONVERSATION_ID)).isFalse();
    }

    @Test
    void setWriteIntentIsRememberedUntilCleared() {
        store.setWriteIntent(CONVERSATION_ID, true);

        assertThat(store.hasWriteIntent(CONVERSATION_ID)).isTrue();
        assertThat(store.hasWriteIntent(CONVERSATION_ID)).isTrue();

        store.setWriteIntent(CONVERSATION_ID, false);

        assertThat(store.hasWriteIntent(CONVERSATION_ID)).isFalse();
    }
}
