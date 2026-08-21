package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ações de escrita à espera de confirmação, indexadas por id.
 *
 * <p>Escopo de aplicação, e não de requisição como o RenderHolder: a confirmação chega num
 * <b>POST diferente</b> (o usuário clica no botão depois da resposta), então a pendência tem que
 * sobreviver ao fim da requisição que a criou. O que impede uma sessão de confirmar a ação de
 * outra é o par (id, sessionId) exigido no resgate — id sozinho não basta.
 *
 * <p>TTL e teto existem porque nada garante que o usuário responda: aba fechada, F5 (que gera
 * sessionId novo) ou simples desistência deixam a pendência órfã para sempre.
 */
@Component
public class PendingActionStore {

    private static final Logger log = LoggerFactory.getLogger(PendingActionStore.class);

    /** Depois disso, confirmar não vale mais: o usuário refaz o pedido no chat. */
    static final Duration TTL = Duration.ofMinutes(15);

    /** Teto de pendências vivas. Estourou, as mais antigas saem — elas já iam expirar. */
    static final int MAX_PENDING = 200;

    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    public PendingAction register(String sessionId, String toolName, String argsJson, ToolCallback callback) {
        return register(sessionId, toolName, argsJson, callback, Map.of());
    }

    public PendingAction register(String sessionId, String toolName, String argsJson, ToolCallback callback,
            Map<String, String> details) {
        purgeExpired();
        enforceCap();
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), sessionId, toolName,
                argsJson == null ? "" : argsJson, callback, Instant.now(), details);
        pending.put(action.id(), action);
        log.info("Ação pendente registrada: id={} tool={} sessionId={}", action.id(), toolName, sessionId);
        return action;
    }

    /**
     * Resgata e <b>remove</b> a pendência. Consumo único de propósito: dois cliques no botão
     * (ou um retry do frontend) executariam a escrita duas vezes, e nenhuma tool de escrita da API
     * é idempotente — createDriver duas vezes é ou dois motoristas ou um erro de e-mail duplicado.
     *
     * @return a ação, ou null se o id não existe, já foi consumido, expirou ou é de outra sessão
     */
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

    /** Descarta o que o usuário deixou para trás. Chamado em toda escrita/leitura do mapa. */
    private void purgeExpired() {
        Instant limit = Instant.now().minus(TTL);
        pending.values().removeIf(action -> action.createdAt().isBefore(limit));
    }

    private void enforceCap() {
        while (pending.size() >= MAX_PENDING) {
            pending.values().stream()
                    .min(Comparator.comparing(PendingAction::createdAt))
                    .map(PendingAction::id)
                    .ifPresent(pending::remove);
        }
    }

    int size() {
        return pending.size();
    }
}
