package br.com.fabio.logisticagent.eval;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

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

    public String argumentsOf(List<String> toolNames) {
        return calls.stream()
                .filter(call -> toolNames.contains(call.name()))
                .map(call -> call.arguments() == null ? "" : call.arguments())
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }
}
