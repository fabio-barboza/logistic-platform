package br.com.fabio.logisticagent.core.agent;

import java.util.Map;

public final class ActionLabels {

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

    private ActionLabels() {
    }

    public static String label(String field) {
        return FIELD_PT.getOrDefault(field, field);
    }

    public static String summary(String toolName) {
        String simpleName = simpleName(toolName);
        return ACTION_PT.getOrDefault(simpleName, "Executar a operação " + simpleName);
    }

    public static boolean destructive(String toolName) {
        return simpleName(toolName).startsWith("delete");
    }

    public static String simpleName(String toolName) {
        return toolName.substring(toolName.lastIndexOf('_') + 1);
    }
}
