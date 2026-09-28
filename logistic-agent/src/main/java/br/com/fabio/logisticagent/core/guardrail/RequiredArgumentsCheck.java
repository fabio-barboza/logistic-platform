package br.com.fabio.logisticagent.core.guardrail;

import br.com.fabio.logisticagent.core.agent.ActionLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RequiredArgumentsCheck(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<String> missingFrom(String toolName, String inputSchema, String argsJson) {
        try {
            JsonNode required = jsonMapper.readTree(nullToEmptyObject(inputSchema)).path("required");
            JsonNode arguments = jsonMapper.readTree(nullToEmptyObject(argsJson));
            if (!required.isArray() || !arguments.isObject()) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode field : required) {
                String name = field.asString();
                if (isBlank(arguments.path(name))) {
                    missing.add(ActionLabels.label(name));
                }
            }
            return missing;
        } catch (JacksonException e) {
            log.warn("Não foi possível checar os obrigatórios de {}: {}", toolName, e.getMessage());
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
