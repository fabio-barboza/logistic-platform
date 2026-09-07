package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Implementação de produção do {@link IPendingActionStore}, sobre a tabela {@code pending_action}. */
@Component
public class PendingActionStoreImpl implements IPendingActionStore {

    private static final Logger log = LoggerFactory.getLogger(PendingActionStoreImpl.class);

    private final IPendingActionRepository repository;

    public PendingActionStoreImpl(IPendingActionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details) {
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), sessionId, toolName,
                argsJson == null ? "" : argsJson, Instant.now(), details);
        repository.save(action);
        log.info("Ação pendente registrada: id={} tool={} sessionId={}", action.id(), toolName, sessionId);
        return action;
    }

    /**
     * O TTL entra na própria busca para uma pendência vencida não ser resgatável antes de a purga
     * passar por ela. O delete decide o consumo único: 0 linhas significa que alguém chegou antes.
     */
    @Override
    @Transactional
    public PendingAction take(String id, String sessionId) {
        PendingAction action = repository
                .findByIdAndSessionIdAndCreatedAtAfter(id, sessionId, Instant.now().minus(TTL))
                .orElse(null);
        if (action == null || repository.deleteConsuming(id, sessionId) == 0) {
            log.info("Confirmação sem pendência correspondente: id={} sessionId={}", id, sessionId);
            return null;
        }
        return action;
    }

    @Override
    public int size() {
        return (int) repository.count();
    }
}
