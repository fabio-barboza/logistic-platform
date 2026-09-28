package br.com.fabio.logisticagent.core.gateway;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPendingActionGateway implements PendingActionGateway {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPendingActionGateway.class);

    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    @Override
    public PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details) {
        purgeExpired();
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), sessionId, toolName,
                argsJson == null ? "" : argsJson, Instant.now(), details);
        pending.put(action.id(), action);
        log.info("Ação pendente registrada: id={} tool={} sessionId={}", action.id(), toolName, sessionId);
        return action;
    }

    @Override
    public PendingAction take(String id, String sessionId) {
        purgeExpired();
        PendingAction action = pending.get(id);
        if (action == null || !action.sessionId().equals(sessionId)) {
            log.info("Confirmação sem pendência correspondente: id={} sessionId={}", id, sessionId);
            return null;
        }
        pending.remove(id);
        return action;
    }

    @Override
    public int size() {
        return pending.size();
    }

    @Override
    public int deleteCreatedBefore(Instant limit) {
        int before = pending.size();
        pending.values().removeIf(action -> action.createdAt().isBefore(limit));
        return before - pending.size();
    }

    private void purgeExpired() {
        Instant limit = Instant.now().minus(TTL);
        pending.values().removeIf(action -> action.createdAt().isBefore(limit));
    }
}
