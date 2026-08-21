package br.com.fabio.logisticagent.confirm;

import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma chamada de tool de escrita interceptada antes de executar, à espera do "confirmar" do usuário.
 *
 * <p>Guarda o {@link ToolCallback} original e o JSON de argumentos <b>exatamente como o modelo
 * mandou</b>: a confirmação reexecuta esse mesmo payload, sem passar de novo pela LLM. Pedir ao
 * modelo para refazer a chamada depois do "sim" é o caminho fácil e errado — ele reescreve valores
 * (nome, capacidade, id) e o usuário confirma uma coisa enquanto outra é gravada.
 *
 * @param id        identificador opaco devolvido ao frontend e exigido de volta na confirmação
 * @param sessionId sessão que originou a ação — a confirmação só vale para a mesma sessão
 * @param toolName  nome da tool MCP (ex.: createDriver)
 * @param argsJson  argumentos crus, no formato que o ToolCallback espera
 * @param callback  a tool real, que só roda quando o usuário confirmar
 * @param createdAt para o TTL do {@link PendingActionStore}
 * @param details   campos do registro alvo, quando a ação é uma exclusão (ver
 *                  {@link DeletionTargetLookup}); vazio nas demais
 */
public record PendingAction(
        String id,
        String sessionId,
        String toolName,
        String argsJson,
        ToolCallback callback,
        Instant createdAt,
        Map<String, String> details) {

    public PendingAction {
        // LinkedHashMap, e não Map.copyOf: mapa imutável do JDK tem ordem de iteração NÃO
        // especificada, e o card saía com "Cidade, Estado, E-mail, Nome" — a ordem das colunas é
        // declarada no DeletionTargetLookup justamente para o usuário ler o registro de cima
        // para baixo.
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** Mesma tool, mesmos argumentos: o modelo repetiu a chamada em vez de esperar. */
    public boolean matches(String otherTool, String otherArgs) {
        return toolName.equals(otherTool) && argsJson.equals(otherArgs == null ? "" : otherArgs);
    }
}
