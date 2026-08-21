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
            Consulta os dados da plataforma com um SELECT em PostgreSQL. Esta é a ÚNICA forma de ler
            dados: qualquer pergunta sobre pedidos, rotas, motoristas ou veículos — listar, contar,
            agrupar, cruzar tabelas, ranquear — é respondida aqui. As demais tools só escrevem.
            Nunca responda com dados sem antes obtê-los desta tool. Roda sobre uma conexão com
            permissão apenas de leitura — INSERT/UPDATE/DELETE/CREATE são recusados pelo próprio banco.

            Regras: só um comando SELECT (ou WITH ... SELECT) por chamada; não use ';'; se não
            informar LIMIT, o resultado é limitado a 50 linhas automaticamente. Para ranking e
            "top N", ordene e limite na própria query em vez de trazer tudo.

            O schema completo vem abaixo — use estes nomes, não os suponha.

            """
            + SchemaText.FULL
            + """

            EXEMPLOS
            --------
            1. Motoristas de SP com mais falhas de entrega:
            SELECT d.id, d.name, COUNT(*) AS falhas
            FROM driver d
            JOIN route r ON r.driver_id = d.id
            JOIN "order" o ON o.route_id = r.id
            WHERE d.state = 'SP' AND o.status = 'DELIVER_FAILURE'
            GROUP BY d.id, d.name
            ORDER BY falhas DESC
            LIMIT 10

            2. Resolver o id de um motorista citado pelo nome, antes de perguntar sobre ele:
            SELECT id, name, city, state FROM driver WHERE name ILIKE '%juliana%'

            3. Cidades com falha de entrega de UM motorista, usando o id do passo anterior:
            SELECT o.city, COUNT(*) AS falhas
            FROM route r
            JOIN "order" o ON o.route_id = r.id
            WHERE r.driver_id = '<id resolvido antes, nunca este literal>' AND o.status = 'DELIVER_FAILURE'
            GROUP BY o.city
            ORDER BY falhas DESC

            4. Contagem simples por status (para gráfico de pizza):
            SELECT status, COUNT(*) AS total FROM "order" GROUP BY status

            5. Pior motorista por estado e total de entregas do estado, numa consulta só:
            WITH por_motorista AS (
              SELECT d.state, d.id, d.name,
                     COUNT(*) FILTER (WHERE o.status = 'DELIVER_FAILURE') AS falhas,
                     COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS entregas
              FROM driver d
              JOIN route r ON r.driver_id = d.id
              JOIN "order" o ON o.route_id = r.id
              GROUP BY d.state, d.id, d.name
            )
            SELECT state, name, falhas,
                   SUM(entregas) OVER (PARTITION BY state) AS entregas_estado
            FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY state ORDER BY falhas DESC) rn
                  FROM por_motorista) t
            WHERE rn = 1
            ORDER BY falhas DESC
            """)
    public String executeQuery(
            @McpToolParam(description = "Consulta SELECT em PostgreSQL. Exemplo: SELECT status, COUNT(*) FROM \"order\" GROUP BY status") String sql) {
        return queryService.executeQuery(sql);
    }
}
