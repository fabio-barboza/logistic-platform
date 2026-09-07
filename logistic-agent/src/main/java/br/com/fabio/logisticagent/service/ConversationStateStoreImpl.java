package br.com.fabio.logisticagent.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de produção do {@link IConversationStateStore}, sobre {@code conversation_state}.
 *
 * <p>Cada operação carrega a linha e o Hibernate cuida do insert ou do update.
 */
@Component
public class ConversationStateStoreImpl implements IConversationStateStore {

    private final IConversationStateRepository repository;

    public ConversationStateStoreImpl(IConversationStateRepository repository) {
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

    private ConversationStateEntity state(String conversationId) {
        return repository.findById(conversationId)
                .orElseGet(() -> repository.save(new ConversationStateEntity(conversationId)));
    }
}
