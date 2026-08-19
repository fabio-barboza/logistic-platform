package br.com.fabio.logisticagent.eval;

/**
 * Uma chamada de tool MCP feita pelo modelo: o nome e os argumentos JSON com que ela foi chamada.
 *
 * <p>Os argumentos entram na avaliação porque escolher a tool certa não basta: {@code searchOrders}
 * sem o filtro de status responde a pergunta errada com a ferramenta certa, e um eval que só olha
 * nomes dá isso como acerto.
 */
public record ToolCall(String name, String arguments) {

    @Override
    public String toString() {
        return name + (arguments == null || arguments.isBlank() ? "" : arguments);
    }
}
