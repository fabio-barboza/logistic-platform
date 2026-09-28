package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.ConversationStateGateway;
import br.com.fabio.logisticagent.core.gateway.ConversationStateGatewayContractTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(ConversationStateGatewayImpl.class)
class ConversationStateGatewayImplTest extends ConversationStateGatewayContractTest {

    @Autowired
    private ConversationStateGatewayImpl store;

    @Override
    protected ConversationStateGateway createStore() {
        return store;
    }
}
