package br.com.fabio.logisticagent.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Guarda quais tools MCP o modelo chamou durante a requisição.
 * <p>
 * Existe porque o modelo responde perguntas de dados sem chamar tool nenhuma quando não é o
 * primeiro turno da sessão: "e em MG?" depois de "pedidos entregues em SP" devolveu uma listagem
 * inteira de MG — com cidade de SP dentro — e um total de 106 onde havia 423, com o log de tool
 * calls vazio nos dois casos. O system prompt já proíbe isso explicitamente e o modelo ignora,
 * então a checagem é aqui: "chamou tool ou não" é fato, não heurística sobre a pergunta.
 * <p>
 * Escopo de requisição pelo mesmo motivo do {@link RenderHolder}: com escopo maior, o turno de um
 * usuário enxergaria as chamadas do turno de outro.
 */
@Component
@RequestScope
public class ToolCallHolder {

    private final List<String> calledTools = new ArrayList<>();

    public void register(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            calledTools.add(toolName);
        }
    }

    public List<String> getCalledTools() {
        return Collections.unmodifiableList(calledTools);
    }

    /** Nenhuma tool chamada nesta requisição — nem busca, nem render, nem escrita. */
    public boolean isEmpty() {
        return calledTools.isEmpty();
    }

    /**
     * Limpa o registro antes de uma nova ida ao modelo. O retry corretivo acontece dentro da mesma
     * requisição, então sem isto a segunda tentativa herdaria as chamadas (ou a ausência delas) da
     * primeira e o teste de "chamou tool" olharia para o turno errado.
     */
    public void reset() {
        calledTools.clear();
    }
}
