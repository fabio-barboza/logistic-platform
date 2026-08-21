package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.RouteStatus;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.dto.RouteRequest;
import br.com.fabio.logistic.dto.RouteResponse;
import br.com.fabio.logistic.service.RouteService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Tools MCP de rota — só escrita; leitura é via executeQuery. */
@Component
public class RouteMcpTools {

    private final RouteService routeService;

    public RouteMcpTools(RouteService routeService) {
        this.routeService = routeService;
    }

    @McpTool(description = """
            Cria uma nova rota para um motorista. Obrigatórios: driverId, status — o status inicial
            normalmente é "IN_PROGRESS". O driverId vem de uma consulta executeQuery anterior; se não
            souber qual motorista, consulte antes em vez de inventar um UUID.
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
            não há transição posterior. Obrigatórios: id, status; o id vem de uma consulta
            executeQuery anterior. Exemplo: id="3fa85f64-...", status="COMPLETED".
            """)
    public RouteResponse updateRouteStatus(
            @McpToolParam(description = "Id (UUID) da rota") UUID id,
            @McpToolParam(description = "Novo status da rota") String status) {
        return routeService.updateStatus(id, RouteStatus.valueOf(status.toUpperCase()));
    }

    @McpTool(description = """
            Aloca um pedido existente (ainda sem rota ou trocando de rota) em uma rota.
            Obrigatórios: orderId, routeId — os ids vêm de uma consulta executeQuery anterior; se não
            souber, consulte antes em vez de inventar um UUID.
            Exemplo: orderId="3fa85f64-...", routeId="7c9e6679-...".
            """)
    public OrderResponse assignOrderToRoute(
            @McpToolParam(description = "Id (UUID) do pedido a ser alocado") UUID orderId,
            @McpToolParam(description = "Id (UUID) da rota de destino") UUID routeId) {
        return routeService.assignOrder(routeId, orderId);
    }
}
