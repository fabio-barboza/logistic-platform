package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.dto.OrderRequest;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.service.OrderService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Tools MCP de pedido — só escrita; leitura é via executeQuery. */
@Component
public class OrderMcpTools {

    private final OrderService orderService;
    private final McpAuthorization mcpAuthorization;

    public OrderMcpTools(OrderService orderService, McpAuthorization mcpAuthorization) {
        this.orderService = orderService;
        this.mcpAuthorization = mcpAuthorization;
    }

    @McpTool(description = """
            Cria um novo pedido de entrega. Obrigatórios: zipCode, neighborhood, city, state, status —
            se o usuário não informou todos, PERGUNTE os que faltam antes de chamar. routeId é
            opcional — se omitido, o pedido fica sem rota alocada (unassigned). Exemplo: zipCode="13000-000", neighborhood="Centro", city="Campinas",
            state="SP", status="IN_ROUTE".
            """)
    public OrderResponse createOrder(
            McpTransportContext ctx,
            @McpToolParam(required = false, description = "Id (UUID) da rota, se já souber qual") UUID routeId,
            @McpToolParam(description = "CEP do endereço de entrega") String zipCode,
            @McpToolParam(description = "Bairro do endereço de entrega") String neighborhood,
            @McpToolParam(description = "Cidade do endereço de entrega") String city,
            @McpToolParam(description = "Sigla do estado (UF), 2 letras") String state,
            @McpToolParam(description = "Status inicial do pedido: IN_ROUTE, COLLECTED, DELIVERED, DELIVER_FAILURE ou CANCELED") String status) {
        mcpAuthorization.require(ctx, "write");
        return orderService.create(new OrderRequest(routeId, zipCode, neighborhood, city, state,
                OrderStatus.valueOf(status.toUpperCase())));
    }

    @McpTool(description = """
            Atualiza o status de um pedido existente. Status possíveis: IN_ROUTE, COLLECTED, DELIVERED,
            DELIVER_FAILURE, CANCELED. DELIVERED e DELIVER_FAILURE são finalizadores — não há transição
            posterior. Obrigatórios: id, status. O id do pedido vem de uma consulta executeQuery
            anterior; se não souber qual pedido, consulte antes em vez de inventar um UUID.
            Exemplo: id="3fa85f64-...", status="DELIVERED".
            """)
    public OrderResponse updateOrderStatus(
            McpTransportContext ctx,
            @McpToolParam(description = "Id (UUID) do pedido") UUID id,
            @McpToolParam(description = "Novo status do pedido") String status) {
        mcpAuthorization.require(ctx, "write");
        return orderService.updateStatus(id, OrderStatus.valueOf(status.toUpperCase()));
    }
}
