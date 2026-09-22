package br.com.fabio.logisticagent.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public boolean isEmpty() {
        return calledTools.isEmpty();
    }

    public void reset() {
        calledTools.clear();
    }
}
