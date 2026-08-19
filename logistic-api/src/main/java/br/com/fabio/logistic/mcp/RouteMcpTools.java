package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.RouteStatus;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.dto.RouteFilter;
import br.com.fabio.logistic.dto.RouteRequest;
import br.com.fabio.logistic.dto.RouteResponse;
import br.com.fabio.logistic.service.RouteService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tools MCP de rota — delegam ao RouteService, sem lógica própria. */
@Component
public class RouteMcpTools {

    private final RouteService routeService;

    public RouteMcpTools(RouteService routeService) {
        this.routeService = routeService;
    }

    @McpTool(description = """
            Busca rotas de entrega, com filtros opcionais combinados em AND. Use para listar rotas por
            status, motorista ou período de criação. Status possíveis: IN_PROGRESS (em andamento),
            COMPLETED (concluída), COMPLETED_WITH_FAILURES (concluída com falhas), CANCELED (cancelada).
            Exemplo: rotas em andamento do motorista "carlos" -> status=["IN_PROGRESS"], driverName="carlos".
            """)
    public List<RouteResponse> searchRoutes(
            @McpToolParam(required = false, description = "Lista de status para filtrar. Valores: IN_PROGRESS, COMPLETED, COMPLETED_WITH_FAILURES, CANCELED. Exemplo: [\"COMPLETED\"]") List<String> status,
            @McpToolParam(required = false, description = "Id do motorista da rota") UUID driverId,
            @McpToolParam(required = false, description = "Parte do nome do motorista da rota. Exemplo: \"joão\"") String driverName,
            @McpToolParam(required = false, description = "Data/hora mínima de criação (ISO). Exemplo: \"2025-01-01T00:00:00\"") LocalDateTime createdFrom,
            @McpToolParam(required = false, description = "Data/hora máxima de criação (ISO). Exemplo: \"2025-12-31T23:59:59\"") LocalDateTime createdTo,
            @McpToolParam(required = false, description = "Quantidade máxima de resultados. Default 25, máximo 100") Integer limit) {
        RouteFilter filter = new RouteFilter(toRouteStatusList(status), driverId, driverName, createdFrom, createdTo);
        Pageable pageable = McpPageSupport.of(limit);
        return routeService.search(filter, pageable).getContent();
    }

    @McpTool(description = "Busca uma rota pelo id. Devolve os dados completos da rota, incluindo o motorista responsável.")
    public RouteResponse getRoute(
            @McpToolParam(description = "Id (UUID) da rota. Exemplo: \"3fa85f64-5717-4562-b3fc-2c963f66afa6\"") UUID id) {
        return routeService.findById(id);
    }

    @McpTool(description = """
            Agrupa rotas e conta quantas existem em cada grupo. Use para montar gráficos como
            "rotas por status" ou "rotas por motorista". Exemplo: groupBy="status" devolve pares como
            {"IN_PROGRESS": 12, "COMPLETED": 30}.
            """)
    public Map<String, Long> countRoutesBy(
            @McpToolParam(description = "Campo de agrupamento: \"status\" ou \"driver\"") String groupBy) {
        return "driver".equalsIgnoreCase(groupBy) ? routeService.countByDriver() : routeService.countByStatus();
    }

    @McpTool(description = """
            Cria uma nova rota para um motorista. Status inicial normalmente é "IN_PROGRESS".
            Exemplo: driverId="3fa85f64-...", status="IN_PROGRESS".
            """)
    public RouteResponse createRoute(
            @McpToolParam(description = "Id (UUID) do motorista responsável pela rota") UUID driverId,
            @McpToolParam(description = "Status inicial da rota: IN_PROGRESS, COMPLETED, COMPLETED_WITH_FAILURES ou CANCELED") String status) {
        return routeService.create(new RouteRequest(driverId, RouteStatus.valueOf(status.toUpperCase())));
    }

    @McpTool(description = """
            Atualiza o status de uma rota existente. Status possíveis: IN_PROGRESS, COMPLETED,
            COMPLETED_WITH_FAILURES, CANCELED. COMPLETED e COMPLETED_WITH_FAILURES são finalizadores —
            não há transição posterior. Exemplo: id="3fa85f64-...", status="COMPLETED".
            """)
    public RouteResponse updateRouteStatus(
            @McpToolParam(description = "Id (UUID) da rota") UUID id,
            @McpToolParam(description = "Novo status da rota") String status) {
        return routeService.updateStatus(id, RouteStatus.valueOf(status.toUpperCase()));
    }

    @McpTool(description = "Lista os pedidos alocados em uma rota.")
    public List<OrderResponse> getRouteOrders(
            @McpToolParam(description = "Id (UUID) da rota") UUID routeId) {
        return routeService.findOrders(routeId);
    }

    @McpTool(description = """
            Aloca um pedido existente (ainda sem rota ou trocando de rota) em uma rota.
            Exemplo: orderId="3fa85f64-...", routeId="7c9e6679-...".
            """)
    public OrderResponse assignOrderToRoute(
            @McpToolParam(description = "Id (UUID) do pedido a ser alocado") UUID orderId,
            @McpToolParam(description = "Id (UUID) da rota de destino") UUID routeId) {
        return routeService.assignOrder(routeId, orderId);
    }

    private List<RouteStatus> toRouteStatusList(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        return status.stream().map(s -> RouteStatus.valueOf(s.toUpperCase())).toList();
    }
}
