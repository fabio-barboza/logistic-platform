package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.service.QueryService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool MCP de SQL read-only — delega ao QueryService, sem lógica própria. */
@Component
public class QueryMcpTools {

    private final QueryService queryService;

    public QueryMcpTools(QueryService queryService) {
        this.queryService = queryService;
    }

    @McpTool(description = """
            Executa uma consulta SELECT livre em PostgreSQL, para perguntas que as outras tools não
            cobrem: cruzamentos entre tabelas (join), agregações (GROUP BY, COUNT, AVG) ou recortes
            fora do catálogo de filtros conhecidos. Rode sobre uma conexão com permissão apenas de
            leitura — INSERT/UPDATE/DELETE/CREATE são recusados pelo próprio banco.

            Tabelas disponíveis: vehicle, driver, driver_vehicle, route, "order" (nome reservado —
            sempre entre aspas duplas). Use a tool describe_schema para ver colunas e enums antes de
            montar a query.

            Regras: só um comando SELECT (ou WITH ... SELECT) por chamada; não use ';'; se não
            informar LIMIT, o resultado é limitado a 500 linhas automaticamente.

            Exemplo 1 — motoristas de SP com mais falhas de entrega em rotas concluídas:
            SELECT d.name, COUNT(*) AS falhas
            FROM driver d
            JOIN route r ON r.driver_id = d.id
            JOIN "order" o ON o.route_id = r.id
            WHERE d.state = 'SP' AND r.status = 'COMPLETED_WITH_FAILURES' AND o.status = 'DELIVER_FAILURE'
            GROUP BY d.name
            ORDER BY falhas DESC
            LIMIT 10

            Exemplo 2 — total de pedidos entregues por veículo:
            SELECT v.name, COUNT(*) AS entregas
            FROM vehicle v
            JOIN driver_vehicle dv ON dv.vehicle_id = v.id
            JOIN driver d ON d.id = dv.driver_id
            JOIN route r ON r.driver_id = d.id
            JOIN "order" o ON o.route_id = r.id
            WHERE o.status = 'DELIVERED'
            GROUP BY v.name

            Exemplo 3 — pedidos criados nos últimos 30 dias por bairro:
            SELECT neighborhood, COUNT(*) FROM "order"
            WHERE created_at >= NOW() - INTERVAL '30 days'
            GROUP BY neighborhood
            ORDER BY COUNT(*) DESC
            """)
    public String executeQuery(
            @McpToolParam(description = "Consulta SELECT em PostgreSQL. Exemplo: SELECT status, COUNT(*) FROM \"order\" GROUP BY status") String sql) {
        return queryService.executeQuery(sql);
    }
}
