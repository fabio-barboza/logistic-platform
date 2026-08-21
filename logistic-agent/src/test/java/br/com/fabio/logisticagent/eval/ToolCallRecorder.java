package br.com.fabio.logisticagent.eval;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registra, em ordem, as tools MCP chamadas pelo modelo durante um caso de eval — nome e argumentos.
 * Preenchido pelo ObservationHandler do {@link EvalTestConfig}, que observa toda chamada de tool
 * feita pelo Spring AI — inclusive as de escrita, que a confirmação intercepta antes de executar.
 */
public class ToolCallRecorder {

    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();

    void record(String toolName, String arguments) {
        calls.add(new ToolCall(toolName, arguments));
    }

    public void reset() {
        calls.clear();
    }

    public List<ToolCall> calls() {
        return List.copyOf(calls);
    }

    public List<String> names() {
        return calls.stream().map(ToolCall::name).toList();
    }

    /**
     * Argumentos de todas as chamadas às tools informadas, concatenados e normalizados para
     * comparação: minúsculas e sem espaços. Assim {@code "state": "SP"} e {@code "state":"sp"}
     * casam com o mesmo trecho esperado no dataset.
     */
    public String argumentsOf(List<String> toolNames) {
        return calls.stream()
                .filter(call -> toolNames.contains(call.name()))
                .map(call -> call.arguments() == null ? "" : call.arguments())
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }
}
