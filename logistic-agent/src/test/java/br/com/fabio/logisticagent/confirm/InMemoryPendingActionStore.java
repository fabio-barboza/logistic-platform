package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dublê para os testes unitários que exercitam classificação de tool, campos obrigatórios e regex,
 * não persistência. {@code PendingActionStoreContractTest} garante que não diverge do JDBC.
 */
public class InMemoryPendingActionStore implements IPendingActionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPendingActionStore.class);

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

    /** Descarta o que o usuário deixou para trás. Chamado em todo acesso ao mapa. */
    private void purgeExpired() {
        Instant limit = Instant.now().minus(TTL);
        pending.values().removeIf(action -> action.createdAt().isBefore(limit));
    }
}
