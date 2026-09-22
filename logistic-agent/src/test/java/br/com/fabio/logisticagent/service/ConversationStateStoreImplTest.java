package br.com.fabio.logisticagent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(ConversationStateStoreImpl.class)
class ConversationStateStoreImplTest extends ConversationStateStoreContractTest {

    @Autowired
    private ConversationStateStoreImpl store;

    @Override
    protected IConversationStateStore createStore() {
        return store;
    }
}
