package br.com.fabio.logistic.mcp;

/**
 * Texto do schema, em constantes, para poder entrar na descrição de mais de uma {@code @McpTool}.
 * <p>
 * Está aqui, e não gerado em runtime, porque descrição de tool é anotação e anotação exige
 * constante de compilação. O preço é sincronia manual com {@code V1__init.sql} e com os enums do
 * domínio — cobrada por {@code SchemaTextTest}, que quebra quando um status novo não aparece aqui.
 * <p>
 * Por que duplicar o schema na descrição do executeQuery em vez de deixar só o describeSchema:
 * descrição de tool está sempre no contexto, tool precisa ser chamada. Numa rodada do eval, 31
 * casos usaram executeQuery e só 2 chamaram describeSchema antes — o modelo escrevia SQL de
 * memória e errava nome de coluna ("zipCode" em vez de zip_code). Isso só se sustenta porque o
 * banco tem 5 tabelas; com um schema grande, o caminho seria devolver o schema no erro da query.
 */
final class SchemaText {

    private SchemaText() {
    }

    static final String TABLES = """
            TABELAS
            -------
            vehicle (id UUID, name VARCHAR, capacity_kg INTEGER, created_at, updated_at)
              Veículos disponíveis na frota. capacity_kg é a capacidade de carga em
              QUILOGRAMAS — a unidade está no nome da coluna, não invente outra.

            driver (id UUID, name VARCHAR, email VARCHAR único, birthday DATE, city VARCHAR,
                    state CHAR(2), created_at, updated_at)
              Motoristas cadastrados. ATENÇÃO: name NÃO é único — existem motoristas homônimos,
              pessoas diferentes, em estados diferentes. A identidade é o id (email também é
              único). Filtrar por name soma homônimos e infla o resultado.

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

            Os nomes das colunas são snake_case, exatamente como escritos acima: zip_code,
            capacity_kg, created_at, driver_id, route_id. Não existe coluna em camelCase.
            """;

    static final String ENUMS = """
            ENUMS E TRADUÇÃO PARA PT-BR
            ---------------------------
            Os valores trafegam em inglês (é o valor gravado no banco e o que vai no SQL).
            A descrição é o que o usuário lê — traduza ao responder.

            route_status:
              IN_PROGRESS              -> Em andamento
              COMPLETED                -> Concluído (finalizador)
              COMPLETED_WITH_FAILURES  -> Concluído com falhas (finalizador)
              CANCELED                 -> Cancelado

            order_status:
              IN_ROUTE                 -> Em rota
              COLLECTED                -> Coletado
              DELIVERED                -> Entregue (finalizador)
              DELIVER_FAILURE          -> Falha na entrega (finalizador)
              CANCELED                 -> Cancelado

            Finalizador = sem transição posterior.
            """;

    static final String RELATIONSHIPS = """
            RELACIONAMENTOS
            ----------------
            driver 1---N route
            route 1---N "order" (route_id pode ser NULL: pedido ainda não alocado)
            driver N---N vehicle (via driver_vehicle)
            """;

    static final String QUERY_RULES = """
            REGRAS AO CONSULTAR
            --------------------
            1. Motorista se identifica por id, nunca por nome. Para perguntar sobre um motorista
               específico, obtenha o id numa consulta executeQuery e filtre por d.id. Se mais de um
               motorista casar com o nome, use o recorte da própria conversa (estado, cidade) para
               escolher; se ainda houver empate, pergunte ao usuário de qual se trata, mostrando
               cidade e estado de cada um. Nunca escolha por conta própria nem some os homônimos.
            2. Ao agregar por motorista, use GROUP BY d.id, d.name (nunca só d.name) e traga d.id
               no SELECT — assim a pergunta seguinte sobre "esse motorista" tem a chave.
            3. Falha de entrega é order.status = 'DELIVER_FAILURE'. Não combine com filtro de
               route.status: são dimensões independentes, e pedidos com falha existem também em
               rotas COMPLETED e CANCELED. Só filtre route.status quando a pergunta for sobre o
               status da rota.
            4. Num follow-up ("e em MG?"), mantenha os filtros que já valiam na pergunta anterior e
               troque só o que o usuário mudou. Reescrever a query do zero perde o recorte.
            """;

    /** Schema inteiro, na ordem em que o modelo precisa ler. */
    static final String FULL = TABLES + "\n" + ENUMS + "\n" + RELATIONSHIPS + "\n" + QUERY_RULES;
}
