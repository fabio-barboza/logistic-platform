package br.com.fabio.logisticagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String setup,
        String question,
        List<String> expectAnyOf,
        List<String> forbid,
        Boolean expectNoTool,
        List<String> expectArgs,
        List<String> forbidArgs,
        Integer maxCalls,
        String render,
        String chartType,
        List<String> expectColumns,
        List<String> expectText,
        List<String> forbidText,
        String pendingAction) {

    public List<String> expectAnyOfOrEmpty() {
        return orEmpty(expectAnyOf);
    }

    public List<String> forbidOrEmpty() {
        return orEmpty(forbid);
    }

    public List<String> expectArgsOrEmpty() {
        return orEmpty(expectArgs);
    }

    public List<String> forbidArgsOrEmpty() {
        return orEmpty(forbidArgs);
    }

    public List<String> expectColumnsOrEmpty() {
        return orEmpty(expectColumns);
    }

    public List<String> expectTextOrEmpty() {
        return orEmpty(expectText);
    }

    public List<String> forbidTextOrEmpty() {
        return orEmpty(forbidText);
    }

    public boolean expectsNoTool() {
        return Boolean.TRUE.equals(expectNoTool);
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
