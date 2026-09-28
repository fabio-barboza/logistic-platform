package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.ConversationStateGateway;
import br.com.fabio.logisticagent.infra.entity.ConversationStateEntity;
import br.com.fabio.logisticagent.infra.repository.ConversationStateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ConversationStateGatewayImpl implements ConversationStateGateway {

    private final ConversationStateRepository repository;

    public ConversationStateGatewayImpl(ConversationStateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasWriteIntent(String conversationId) {
        return repository.findById(conversationId)
                .map(ConversationStateEntity::isWriteIntent)
                .orElse(false);
    }

    @Override
    @Transactional
    public void setWriteIntent(String conversationId, boolean requested) {
        state(conversationId).setWriteIntent(requested);
    }

    @Override
    @Transactional
    public int deleteUpdatedBefore(Instant limit) {
        return repository.deleteStale(limit);
    }

    private ConversationStateEntity state(String conversationId) {
        return repository.findById(conversationId)
                .orElseGet(() -> repository.save(new ConversationStateEntity(conversationId)));
    }
}
