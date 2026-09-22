package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RequiredArgumentsCheck {

    private static final Logger log = LoggerFactory.getLogger(RequiredArgumentsCheck.class);

    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "-", "--", "null", "nil", "none", "n/a", "na", "string", "?",
            "desconhecido", "nao informado", "não informado", "a definir", "todo");

    private final JsonMapper jsonMapper;
    private final PendingActionMapper labels;

    public RequiredArgumentsCheck(JsonMapper jsonMapper, PendingActionMapper labels) {
        this.jsonMapper = jsonMapper;
        this.labels = labels;
    }

    public List<String> missingFrom(ToolDefinition tool, String argsJson) {
        try {
            JsonNode required = jsonMapper.readTree(nullToEmptyObject(tool.inputSchema())).path("required");
            JsonNode arguments = jsonMapper.readTree(nullToEmptyObject(argsJson));
            if (!required.isArray() || !arguments.isObject()) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode field : required) {
                String name = field.asString();
                if (isBlank(arguments.path(name))) {
                    missing.add(labels.label(name));
                }
            }
            return missing;
        } catch (JacksonException e) {
            log.warn("Não foi possível checar os obrigatórios de {}: {}", tool.name(), e.getMessage());
            return List.of();
        }
    }

    private boolean isBlank(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return true;
        }
        if (!value.isString()) {
            return false;
        }
        return PLACEHOLDERS.contains(value.asString().strip().toLowerCase(Locale.ROOT));
    }

    private String nullToEmptyObject(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }
}
