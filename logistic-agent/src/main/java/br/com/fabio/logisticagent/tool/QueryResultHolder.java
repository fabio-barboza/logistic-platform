package br.com.fabio.logisticagent.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guarda as linhas devolvidas pelo executeQuery nesta requisição.
 *
 * <p>É o que permite ao render trabalhar por referência: o modelo diz qual coluna é rótulo e qual
 * é valor, e o código monta o gráfico a partir daqui. Enquanto os valores vinham digitados pelo
 * modelo nos argumentos da tool, ele podia inventá-los — e inventava; agora o número que aparece
 * na tela é necessariamente o número que o banco devolveu.
 *
 * <p>Escopo de requisição pelo mesmo motivo do {@link RenderHolder}: com escopo maior, o turno de
 * um usuário renderizaria os dados do turno de outro.
 */
@Component
@RequestScope
public class QueryResultHolder {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Só o último resultado: é dele que a pergunta em curso está falando. */
    private List<Map<String, String>> rows = List.of();

    /**
     * Recebe o retorno cru da tool. O MCP embrulha em {@code [{"text":"[{...}]"}]} — mesmo
     * desembrulho de {@code DeletionTargetLookup}. Retorno que não seja uma lista de objetos
     * (erro de SQL, texto de recusa) simplesmente não vira linha nenhuma.
     */
    public void register(String rawResult) {
        try {
            JsonNode node = MAPPER.readTree(rawResult == null ? "[]" : rawResult);
            if (node.isArray() && !node.isEmpty() && node.get(0).path("text").isString()) {
                node = MAPPER.readTree(node.get(0).path("text").asString());
            }
            if (!node.isArray()) {
                return;
            }
            List<Map<String, String>> parsed = new ArrayList<>();
            for (JsonNode row : node) {
                if (!row.isObject()) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                row.properties().forEach(entry -> values.put(entry.getKey(),
                        entry.getValue().isNull() ? "" : entry.getValue().asString()));
                parsed.add(values);
            }
            this.rows = List.copyOf(parsed);
        } catch (RuntimeException e) {
            this.rows = List.of();
        }
    }

    public List<Map<String, String>> rows() {
        return rows;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Colunas disponíveis, na ordem do SELECT — a tool devolve isto ao modelo quando ele erra o nome. */
    public List<String> columns() {
        return rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
    }

    public boolean hasColumn(String name) {
        return name != null && !rows.isEmpty() && rows.get(0).containsKey(name);
    }

    public List<String> column(String name) {
        return rows.stream().map(row -> row.getOrDefault(name, "")).toList();
    }
}
