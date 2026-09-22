package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

@Component
public class DeletionTargetLookup {

    private static final Logger log = LoggerFactory.getLogger(DeletionTargetLookup.class);

    private static final Map<String, List<Column>> COLUMNS_BY_TOOL = Map.of(
            "deleteDriver", List.of(new Column("name", "name"), new Column("email", "email"),
                    new Column("city", "city"), new Column("state", "state")),
            "deleteVehicle", List.of(new Column("name", "name"),
                    new Column("capacity_kg AS \"capacityKg\"", "capacityKg")));

    private static final Map<String, String> TABLE_BY_TOOL = Map.of(
            "deleteDriver", "driver",
            "deleteVehicle", "vehicle");

    private record Column(String expression, String key) {
    }

    private static final Map<String, String> ENTITY_PT = Map.of(
            "deleteDriver", "motorista",
            "deleteVehicle", "veículo");

    private final JsonMapper jsonMapper;
    private final PendingActionMapper labels;

    public DeletionTargetLookup(JsonMapper jsonMapper, PendingActionMapper labels) {
        this.jsonMapper = jsonMapper;
        this.labels = labels;
    }

    public boolean supports(String toolName) {
        return COLUMNS_BY_TOOL.containsKey(simpleName(toolName));
    }

    public String entityOf(String toolName) {
        return ENTITY_PT.getOrDefault(simpleName(toolName), "registro");
    }

    public Optional<Map<String, String>> describe(String toolName, String argsJson, ToolCallback queryTool) {
        List<Column> columns = COLUMNS_BY_TOOL.get(simpleName(toolName));
        if (columns == null || queryTool == null) {
            return Optional.empty();
        }
        Optional<UUID> id = idFrom(argsJson);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        try {
            String sql = "SELECT " + columns.stream().map(Column::expression).collect(joining(", "))
                    + " FROM " + TABLE_BY_TOOL.get(simpleName(toolName)) + " WHERE id = '" + id.get() + "'";
            JsonNode rows = unwrap(queryTool.call(jsonMapper.writeValueAsString(Map.of("sql", sql))));
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(fields(columns, rows.get(0)));
        } catch (RuntimeException e) {

            log.warn("Não foi possível descrever o alvo de {}: {}", toolName, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, String> fields(List<Column> columns, JsonNode row) {
        Map<String, String> details = new LinkedHashMap<>();
        for (Column column : columns) {
            JsonNode value = row.path(column.key());
            if (!value.isMissingNode() && !value.isNull()) {
                details.put(labels.label(column.key()), value.asString());
            }
        }
        return details;
    }

    private Optional<UUID> idFrom(String argsJson) {
        try {
            JsonNode args = jsonMapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            for (String key : Set.of("id", "driverId", "vehicleId")) {
                JsonNode value = args.path(key);
                if (value.isString()) {
                    return Optional.of(UUID.fromString(value.asString().strip()));
                }
            }
        } catch (JacksonException | IllegalArgumentException e) {
            log.info("Id inválido nos argumentos da exclusão: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private JsonNode unwrap(String result) {
        JsonNode node = jsonMapper.readTree(result == null ? "[]" : result);
        if (node.isArray() && !node.isEmpty() && node.get(0).path("text").isString()) {
            return jsonMapper.readTree(node.get(0).path("text").asString());
        }
        return node;
    }

    private String simpleName(String toolName) {
        return toolName.substring(toolName.lastIndexOf('_') + 1);
    }
}
