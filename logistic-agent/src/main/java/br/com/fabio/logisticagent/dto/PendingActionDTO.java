package br.com.fabio.logisticagent.dto;

import java.util.Map;

/**
 * Ação de escrita aguardando o "confirmar" do usuário, como o frontend a recebe.
 *
 * <p>Não carrega o ToolCallback nem o JSON cru: o que o usuário precisa ver é o que será feito, e
 * o que o backend precisa de volta é só o id — os argumentos que serão executados continuam do
 * lado do servidor, para o payload confirmado ser exatamente o registrado.
 *
 * @param id        devolver no POST /api/chat/confirm
 * @param tool      nome da tool MCP, para diagnóstico
 * @param summary   frase em PT-BR do que será feito
 * @param arguments argumentos do modelo, campo a campo, para o usuário conferir antes de confirmar
 * @param destructive exclusão (irreversível): o frontend pinta o card e o botão de outra cor
 */
public record PendingActionDTO(String id, String tool, String summary, Map<String, String> arguments,
                               boolean destructive) {
}
