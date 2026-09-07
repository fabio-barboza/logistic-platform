package br.com.fabio.logisticagent.confirm;

import java.time.Duration;
import java.util.Map;

/**
 * Ações de escrita à espera de confirmação, indexadas por id.
 *
 * <p>A confirmação chega num <b>POST diferente</b> (o usuário clica no botão depois da resposta),
 * e nada garante que ele volte para o mesmo processo que registrou a pendência — daí a
 * implementação de produção ser uma tabela ({@link JdbcPendingActionStore}). O que impede uma
 * sessão de confirmar a ação de outra é o par (id, sessionId) exigido no resgate: id sozinho não
 * basta.
 *
 * <p>O TTL existe porque nada garante que o usuário responda: aba fechada, F5 (que gera sessionId
 * novo) ou desistência deixam a pendência órfã. O corte é aplicado no {@link #take} e na purga
 * agendada ({@code AgentStatePurge}).
 */
public interface IPendingActionStore {

    /** Depois disso, confirmar não vale mais: o usuário refaz o pedido no chat. */
    Duration TTL = Duration.ofMinutes(15);

    default PendingAction register(String sessionId, String toolName, String argsJson) {
        return register(sessionId, toolName, argsJson, Map.of());
    }

    PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details);

    /**
     * Resgata e <b>remove</b>. Consumo único porque nenhuma tool de escrita da API é idempotente:
     * dois cliques no botão gravariam duas vezes.
     *
     * @return a ação, ou null se o id não existe, já foi consumido, expirou ou é de outra sessão
     */
    PendingAction take(String id, String sessionId);

    /** Usado só por teste, para conferir o efeito do TTL e do consumo único. */
    int size();
}
