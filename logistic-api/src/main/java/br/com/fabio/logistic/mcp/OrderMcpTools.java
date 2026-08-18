package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.dto.OrderFilter;
import br.com.fabio.logistic.dto.OrderRequest;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.service.OrderService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tools MCP de pedido — delegam ao OrderService, sem lógica própria. */
@Component
public class OrderMcpTools {

    private final OrderService orderService;

    public OrderMcpTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @McpTool(description = """
            Busca pedidos de entrega, com filtros opcionais combinados em AND. Use para listar pedidos
            por status, localização (cidade, estado, bairro, CEP), rota ou período. Status possíveis:
            IN_ROUTE (em rota), COLLECTED (coletado), DELIVERED (entregue), DELIVER_FAILURE (falha na
            entrega), CANCELED (cancelado). unassigned=true traz só pedidos sem rota alocada.
            Exemplo: pedidos entregues em SP -> state="SP", status=["DELIVERED"].
            """)
    public List<OrderResponse> searchOrders(
            @McpToolParam(required = false, description = "Lista de status para filtrar. Valores: IN_ROUTE, COLLECTED, DELIVERED, DELIVER_FAILURE, CANCELED. Exemplo: [\"DELIVERED\", \"IN_ROUTE\"]") List<String> status,
            @McpToolParam(required = false, description = "Id da rota à qual os pedidos pertencem") UUID routeId,
            @McpToolParam(required = false, description = "Cidade de entrega. Exemplo: \"Campinas\"") String city,
            @McpToolParam(required = false, description = "Sigla do estado (UF), 2 letras. Exemplo: \"SP\"") String state,
            @McpToolParam(required = false, description = "Parte do nome do bairro. Exemplo: \"centro\"") String neighborhood,
            @McpToolParam(required = false, description = "CEP exato de entrega") String zipCode,
            @McpToolParam(required = false, description = "Data/hora mínima de criação (ISO). Exemplo: \"2025-01-01T00:00:00\"") LocalDateTime createdFrom,
            @McpToolParam(required = false, description = "Data/hora máxima de criação (ISO). Exemplo: \"2025-12-31T23:59:59\"") LocalDateTime createdTo,
            @McpToolParam(required = false, description = "true para trazer só pedidos ainda sem rota alocada (route_id nulo)") Boolean unassigned,
            @McpToolParam(required = false, description = "Quantidade máxima de resultados. Default 100, máximo 500") Integer limit) {
        OrderFilter filter = new OrderFilter(toOrderStatusList(status), routeId, city, state,
                neighborhood, zipCode, createdFrom, createdTo, unassigned);
        Pageable pageable = McpPageSupport.of(limit);
        return orderService.search(filter, pageable).getContent();
    }

    @McpTool(description = "Busca um pedido pelo id. Devolve os dados completos do pedido, incluindo a rota (se houver).")
    public OrderResponse getOrder(
            @McpToolParam(description = "Id (UUID) do pedido. Exemplo: \"3fa85f64-5717-4562-b3fc-2c963f66afa6\"") UUID id) {
        return orderService.findById(id);
    }

    @McpTool(description = """
            Agrupa pedidos e conta quantos existem em cada grupo. Use para montar gráficos como
            "pedidos por status", "pedidos por estado", "pedidos por cidade" ou "pedidos por bairro".
            Exemplo: groupBy="status" devolve pares como {"DELIVERED": 120, "IN_ROUTE": 15}.
            """)
    public Map<String, Long> countOrdersBy(
            @McpToolParam(description = "Campo de agrupamento: \"status\", \"state\", \"city\" ou \"neighborhood\"") String groupBy) {
        return orderService.countBy(groupBy);
    }

    @McpTool(description = """
            Cria um novo pedido de entrega. routeId é opcional — se omitido, o pedido fica sem rota
            alocada (unassigned). Exemplo: zipCode="13000-000", neighborhood="Centro", city="Campinas",
            state="SP", status="IN_ROUTE".
            """)
    public OrderResponse createOrder(
            @McpToolParam(required = false, description = "Id (UUID) da rota, se já souber qual") UUID routeId,
            @McpToolParam(description = "CEP do endereço de entrega") String zipCode,
            @McpToolParam(description = "Bairro do endereço de entrega") String neighborhood,
            @McpToolParam(description = "Cidade do endereço de entrega") String city,
            @McpToolParam(description = "Sigla do estado (UF), 2 letras") String state,
            @McpToolParam(description = "Status inicial do pedido: IN_ROUTE, COLLECTED, DELIVERED, DELIVER_FAILURE ou CANCELED") String status) {
        return orderService.create(new OrderRequest(routeId, zipCode, neighborhood, city, state,
                OrderStatus.valueOf(status.toUpperCase())));
    }

    @McpTool(description = """
            Atualiza o status de um pedido existente. Status possíveis: IN_ROUTE, COLLECTED, DELIVERED,
            DELIVER_FAILURE, CANCELED. DELIVERED e DELIVER_FAILURE são finalizadores — não há transição
            posterior. Exemplo: id="3fa85f64-...", status="DELIVERED".
            """)
    public OrderResponse updateOrderStatus(
            @McpToolParam(description = "Id (UUID) do pedido") UUID id,
            @McpToolParam(description = "Novo status do pedido") String status) {
        return orderService.updateStatus(id, OrderStatus.valueOf(status.toUpperCase()));
    }

    private List<OrderStatus> toOrderStatusList(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        return status.stream().map(s -> OrderStatus.valueOf(s.toUpperCase())).toList();
    }
}
