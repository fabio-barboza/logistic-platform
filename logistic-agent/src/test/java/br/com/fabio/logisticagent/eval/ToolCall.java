package br.com.fabio.logisticagent.eval;

public record ToolCall(String name, String arguments) {

    @Override
    public String toString() {
        return name + (arguments == null || arguments.isBlank() ? "" : arguments);
    }
}
