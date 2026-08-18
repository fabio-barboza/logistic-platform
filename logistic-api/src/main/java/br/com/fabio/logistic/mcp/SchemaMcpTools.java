package br.com.fabio.logistic.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Tool que descreve o schema do banco para o modelo — existe para tirar o schema do system
 * prompt do agent. Mantenha completa e atualizada junto com V1__init.sql.
 */
@Component
public class SchemaMcpTools {

    @McpTool(description = """
            Descreve as tabelas, campos, enums e a tradução de status PT-BR do banco de logística.
            Use antes de montar uma query para execute_query, para saber os nomes exatos das tabelas
            e colunas.
            """)
    public String describeSchema() {
        return """
                TABELAS
                -------
                vehicle (id UUID, name VARCHAR, capacity INTEGER, created_at, updated_at)
                  Veículos disponíveis na frota.

                driver (id UUID, name VARCHAR, email VARCHAR único, birthday DATE, city VARCHAR,
                        state CHAR(2), created_at, updated_at)
                  Motoristas cadastrados.

                driver_vehicle (id UUID, driver_id UUID -> driver.id, vehicle_id UUID -> vehicle.id,
                                created_at)
                  Associação N:N entre motorista e veículo. Par (driver_id, vehicle_id) é único.

                route (id UUID, driver_id UUID -> driver.id, status route_status, created_at, updated_at)
                  Rotas de entrega atribuídas a um motorista.

                "order" (id UUID, route_id UUID -> route.id (pode ser NULL), zip_code VARCHAR,
                         neighborhood VARCHAR, city VARCHAR, state CHAR(2), status order_status,
                         created_at, updated_at)
                  Pedidos de entrega. "order" é palavra reservada no Postgres — sempre usar entre
                  aspas duplas em SQL: SELECT * FROM "order".

                ENUMS
                -----
                route_status: COMPLETED, COMPLETED_WITH_FAILURES, CANCELED, IN_PROGRESS
                order_status: DELIVERED, IN_ROUTE, COLLECTED, CANCELED, DELIVER_FAILURE

                TRADUÇÃO DE STATUS (usar ao responder ao usuário)
                ---------------------------------------------------
                route_status.COMPLETED               -> Concluído
                route_status.COMPLETED_WITH_FAILURES  -> Concluído com falhas
                route_status.CANCELED                 -> Cancelado
                route_status.IN_PROGRESS              -> Em andamento
                order_status.DELIVERED                -> Entregue
                order_status.IN_ROUTE                 -> Em rota
                order_status.COLLECTED                -> Coletado
                order_status.DELIVER_FAILURE           -> Falha na entrega
                order_status.CANCELED                 -> Cancelado

                Status finalizadores (sem transição futura): route.COMPLETED, route.COMPLETED_WITH_FAILURES,
                order.DELIVERED, order.DELIVER_FAILURE.

                RELACIONAMENTOS
                ----------------
                driver 1---N route
                route 1---N "order" (route_id pode ser NULL: pedido ainda não alocado)
                driver N---N vehicle (via driver_vehicle)
                """;
    }
}
