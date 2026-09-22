package br.com.fabio.logisticagent.confirm;

import br.com.fabio.logisticagent.dto.PendingActionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PendingActionMapper {

    private static final Logger log = LoggerFactory.getLogger(PendingActionMapper.class);

    private static final Map<String, String> ACTION_PT = Map.ofEntries(
            Map.entry("createDriver", "Cadastrar um novo motorista"),
            Map.entry("linkDriverVehicle", "Vincular um veículo a um motorista"),
            Map.entry("createVehicle", "Cadastrar um novo veículo"),
            Map.entry("createOrder", "Cadastrar um novo pedido"),
            Map.entry("updateOrderStatus", "Alterar o status de um pedido"),
            Map.entry("createRoute", "Criar uma nova rota"),
            Map.entry("updateRouteStatus", "Alterar o status de uma rota"),
            Map.entry("assignOrderToRoute", "Vincular um pedido a uma rota"),
            Map.entry("deleteDriver", "EXCLUIR um motorista (irreversível)"),
            Map.entry("deleteVehicle", "EXCLUIR um veículo da frota (irreversível)"));

    private static final Map<String, String> FIELD_PT = Map.ofEntries(
            Map.entry("id", "Id"),
            Map.entry("name", "Nome"),
            Map.entry("email", "E-mail"),
            Map.entry("birthday", "Nascimento"),
            Map.entry("city", "Cidade"),
            Map.entry("state", "Estado"),
            Map.entry("street", "Rua"),
            Map.entry("number", "Número"),
            Map.entry("zipCode", "CEP"),
            Map.entry("status", "Status"),
            Map.entry("capacityKg", "Capacidade (kg)"),
            Map.entry("plate", "Placa"),
            Map.entry("model", "Modelo"),
            Map.entry("driverId", "Id do motorista"),
            Map.entry("vehicleId", "Id do veículo"),
            Map.entry("orderId", "Id do pedido"),
            Map.entry("routeId", "Id da rota"));

    private final JsonMapper jsonMapper;

    public PendingActionMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public PendingActionDTO toDto(PendingAction action) {
        return new PendingActionDTO(action.id(), action.toolName(), summary(action.toolName()),
                displayed(action), destructive(action.toolName()));
    }

    private Map<String, String> displayed(PendingAction action) {
        if (action.details().isEmpty()) {
            return arguments(action.argsJson());
        }
        Map<String, String> displayed = new LinkedHashMap<>(action.details());
        displayed.putAll(arguments(action.argsJson()));
        return displayed;
    }

    private boolean destructive(String toolName) {
        return simpleName(toolName).startsWith("delete");
    }

    private String simpleName(String toolName) {
        return toolName.substring(toolName.lastIndexOf('_') + 1);
    }

    public String label(String field) {
        return FIELD_PT.getOrDefault(field, field);
    }

    private String summary(String toolName) {
        String simpleName = simpleName(toolName);
        return ACTION_PT.getOrDefault(simpleName, "Executar a operação " + simpleName);
    }

    private Map<String, String> arguments(String argsJson) {
        Map<String, String> arguments = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = jsonMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
            });
            parsed.forEach((key, value) -> arguments.put(label(key), String.valueOf(value)));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Argumentos da ação {} não são um JSON de objeto: {}", argsJson, e.getMessage());
            arguments.put("Argumentos", argsJson);
        }
        return arguments;
    }
}
