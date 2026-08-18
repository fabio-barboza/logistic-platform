package br.com.fabio.logisticagent.eval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registra, em ordem, os nomes das tools MCP chamadas pelo modelo durante um caso de eval.
 * Preenchido pelo {@link RecordingToolCallbackProvider}, que embrulha o provider real.
 */
public class ToolCallRecorder {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    void record(String toolName) {
        calls.add(toolName);
    }

    public void reset() {
        calls.clear();
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public boolean called(String toolName) {
        return calls.contains(toolName);
    }
}
