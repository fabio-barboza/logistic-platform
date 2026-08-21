package br.com.fabio.logisticagent.eval;

/**
 * Uma chamada de tool MCP feita pelo modelo: o nome e os argumentos JSON com que ela foi chamada.
 *
 * <p>Os argumentos entram na avaliação porque escolher a tool certa não basta: com a leitura toda
 * concentrada em {@code executeQuery}, o nome da tool é sempre o mesmo — o que distingue acerto de
 * erro é o SQL, e um eval que só olha nomes dá qualquer query como acerto.
 */
public record ToolCall(String name, String arguments) {

    @Override
    public String toString() {
        return name + (arguments == null || arguments.isBlank() ? "" : arguments);
    }
}
