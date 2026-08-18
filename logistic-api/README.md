# logistic-api

Dona do domínio e do banco. Expõe as mesmas operações por dois caminhos — REST (`@RestController`)
para humanos e MCP (`@McpTool`) para a LLM — que chamam **a mesma camada service**. Nenhuma regra
de negócio é duplicada entre os dois.

Visão geral e como subir tudo junto: [README da raiz](../README.md).

## Rodar isolado

```bash
docker compose -f docker-compose.yaml up -d      # Postgres 18 (pgvector) na 5432
./mvnw spring-boot:run                           # API na 8081, Flyway cria o schema
```

Popular com dados de demonstração (o `dados.sql` começa com `TRUNCATE ... CASCADE`):

```bash
docker exec -i logisticdb psql -U postgres -d logisticdb < src/main/resources/db/seed/dados.sql
```

## Modelo de dados

```
vehicle ◄──── driver_vehicle ────► driver
                                      │
                                    route  (IN_PROGRESS | COMPLETED | COMPLETED_WITH_FAILURES | CANCELED)
                                      │
                                   "order" (IN_ROUTE | COLLECTED | DELIVERED | DELIVER_FAILURE | CANCELED)
```

| Tabela | Campos |
|--------|--------|
| `vehicle` | id, name, capacity, created_at, updated_at |
| `driver` | id, name, email (unique), birthday, city, state (char 2), created_at, updated_at |
| `driver_vehicle` | id, driver_id, vehicle_id, created_at (unique driver+vehicle) |
| `route` | id, driver_id, status (enum nativo `route_status`), created_at, updated_at |
| `"order"` | id, route_id (nullable), zip_code, neighborhood, city, state, status (enum nativo `order_status`), created_at, updated_at |

`order` é palavra reservada: sempre entre aspas duplas em SQL, `@Table(name = "\"order\"")` em JPA.

Migrações em `src/main/resources/db/migration`: `V1__init.sql` (schema) e
`V2__readonly_role.sql` (role `logistic_ro`).

## Endpoints REST

Documentação interativa em <http://localhost:8081/swagger-ui.html>.

| Método | Rota |
|--------|------|
| `GET` `POST` | `/api/drivers` |
| `GET` `PUT` `DELETE` | `/api/drivers/{id}` |
| `POST` `DELETE` | `/api/drivers/{id}/vehicles/{vehicleId}` |
| `GET` `POST` | `/api/vehicles` |
| `GET` `PUT` `DELETE` | `/api/vehicles/{id}` |
| `GET` `POST` | `/api/routes` |
| `GET` `PUT` `DELETE` | `/api/routes/{id}` |
| `GET` | `/api/routes/{id}/orders` |
| `POST` | `/api/routes/{id}/orders/{orderId}` |
| `GET` `POST` | `/api/orders` |
| `GET` `PUT` `DELETE` | `/api/orders/{id}` |

## Filtros por entidade

Os `GET` de coleção aceitam filtros como query params, todos opcionais e combináveis
(`AND` entre os informados). Cada um vira um campo do `*Filter` correspondente e é resolvido
em JPQL com o padrão `:param IS NULL OR ...` — sem Criteria API, sem Specification.

| Entidade | Filtros |
|----------|---------|
| `driver` | `name` (contém, case-insensitive), `email` (contém), `city`, `state`, `birthdayFrom`, `birthdayTo`, `vehicleId` |
| `vehicle` | `name` (contém), `capacityMin`, `capacityMax`, `driverId` |
| `route` | `status` (lista), `driverId`, `driverName` (contém), `createdFrom`, `createdTo` |
| `order` | `status` (lista), `routeId`, `city`, `state`, `neighborhood`, `zipCode`, `createdFrom`, `createdTo`, `unassigned` |

Paginação no REST é a do Spring Data: `page`, `size` e `sort`. As tools MCP não expõem
`Pageable` — usam um `limit` simples (default 100, teto 500), traduzido para `Pageable` em
`McpPageSupport`, porque uma LLM lida melhor com um número do que com um objeto de paginação.

## Tools MCP

Transporte **Streamable HTTP**, endpoint `http://localhost:8081/mcp`.

| Tool | O que faz |
|------|-----------|
| `searchDrivers` / `getDriver` / `createDriver` | motoristas |
| `linkDriverVehicle` | vincula veículo a motorista (`driver_vehicle`) |
| `searchVehicles` / `getVehicle` / `createVehicle` | veículos |
| `searchRoutes` / `getRoute` / `createRoute` / `updateRouteStatus` | rotas |
| `countRoutesBy` | contagem de rotas agrupada por `status` ou `driver` |
| `getRouteOrders` / `assignOrderToRoute` | pedidos de uma rota |
| `searchOrders` / `getOrder` / `createOrder` / `updateOrderStatus` | pedidos |
| `countOrdersBy` | contagem de pedidos agrupada |
| `describeSchema` | descreve entidades, campos e valores de enum para a LLM |
| `executeQuery` | `SELECT` livre, escrito pela LLM, sobre a conexão read-only |

As descrições das tools e dos parâmetros são o que a LLM lê para decidir o que chamar —
mudar o texto muda o comportamento do modelo. Trate-as como código.

### `executeQuery` e a role read-only

A tool existe para as perguntas que nenhuma tool tipada cobre: join entre entidades, agregação,
recorte imprevisto. O SQL é escrito pelo modelo, mas executado **dentro da API**, sobre um
`DataSource` separado que autentica como `logistic_ro` — role com `SELECT` e nada mais.

A garantia contra escrita vive no Postgres, via `GRANT`/`REVOKE` (`V2__readonly_role.sql`), não em
regex no Java. As checagens em `QueryService` (recusa `;`, exige `SELECT`/`WITH`, injeta
`LIMIT 500`) são conveniência para o modelo se corrigir rápido, não a defesa.

A conexão read-only também roda com `statement_timeout = 30s`, configurado em `application.yml`.

## Inspecionar o MCP server

Com a API no ar:

```bash
npx @modelcontextprotocol/inspector
```

No Inspector, escolha transporte **Streamable HTTP** e a URL `http://localhost:8081/mcp`.
`tools/list` mostra o catálogo exatamente como a LLM o enxerga — nomes, descrições e JSON Schema
de cada parâmetro. É o jeito mais rápido de conferir se uma mudança de assinatura chegou no modelo.

## Configuração

| Chave | Padrão |
|-------|--------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/logisticdb?prepareThreshold=0` |
| `logistic.readonly-datasource.username` | `logistic_ro` |
| `server.port` | 8081 |

O `prepareThreshold=0` desliga prepared statements no lado do servidor. Sem isso o Postgres não
infere o tipo de alguns parâmetros nas queries JPQL com `IN :lista` e `CAST(:param AS string)` do
padrão de filtro dinâmico (erro "could not determine data type of parameter").
