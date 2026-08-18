# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Três apps independentes num só repo (não é multi-módulo Maven — cada um tem seu `pom.xml`/`package.json`):

| Diretório | Stack | Porta |
|-----------|-------|-------|
| `logistic-webui/` | Vite 8, Chart.js 4, marked, JS puro (sem framework) | 5173 |
| `logistic-agent/` | Java 21, Spring Boot 4.0.6, Spring AI 2.0.0 (MCP **client**) | 8080 |
| `logistic-api/` | Java 21, Spring Boot 4.0.6, JPA, Flyway, MCP **server** | 8081 |

Postgres 18 (pgvector) em 5432, via `logistic-api/docker-compose.yaml` (container `logisticdb`).
LLM local OpenAI-compatível esperada em `http://localhost:8200` (`qwen3.6:35b`) — opcional para subir a stack, obrigatória para o chat responder.

Regra estruturante: **a LLM nunca toca o banco.** Ela chama tools MCP expostas pela `logistic-api`; só a API executa SQL. O `logistic-agent` não tem datasource nem dependência de banco no `pom.xml` — se aparecer a necessidade de dados lá, a resposta é uma tool MCP nova na API, não um repositório no agent.

## Comandos

Stack inteira (sobe Postgres → API → seed → agent → webui, na ordem, com espera entre etapas):

```bash
./start.sh              # Linux/macOS; Ctrl+C derruba tudo
./start.sh --build      # recompila api e agent + npm install no webui
./start.sh --reset      # limpa o banco e reaplica dados.sql (pede confirmação; --yes pula)
./start.sh --no-build --no-seed --help
.\start.bat             # Windows (wrapper do start.ps1, mesmas flags com '-')
```

**O padrão não compila.** Mudou Java ou JS e rodou `./start.sh` sem `--build`? A stack sobe com o artefato antigo. Exceção: sem jar ou sem `node_modules`, compila sozinho.

Build e testes por app:

```bash
cd logistic-api   && ./mvnw test                                   # todos
cd logistic-api   && ./mvnw test -Dtest=OrderControllerTest        # uma classe
cd logistic-api   && ./mvnw test -Dtest=OrderControllerTest#nomeDoMetodo  # um método
cd logistic-api   && ./mvnw clean package                          # jar
cd logistic-agent && ./mvnw test
cd logistic-webui && npm run dev | npm run build
```

Testes da API rodam em H2 (`MODE=PostgreSQL`, Flyway desligado, perfil `test`) — não precisam de Docker. `@DataJpaTest` exige `@ActiveProfiles("test")`; testes de controller usam `@WebMvcTest` + `@MockitoBean` no service.

Logs de cada app vão para `logs/logistic-{api,agent,webui}.log` — o terminal do `start.sh` só mostra progresso. Para diagnosticar subida, é `tail` nesses arquivos.

## Arquitetura

### logistic-api — dono do domínio

Camadas: `controller/` (REST) e `mcp/` (tools) são **dois adaptadores sobre o mesmo `service/`**. Nem controller nem tool MCP têm lógica; regra de negócio mora só em `service/`. Ao adicionar um caso de uso, mexa nos três: service (lógica), controller (REST), MCP tools (LLM).

- `repository/`: filtro dinâmico via JPQL com `(:param IS NULL OR ...)`. Parâmetros string/uuid/timestamp exigem `CAST(:param AS tipo)` — o Postgres não infere o tipo sozinho. Por isso também o `prepareThreshold=0` na URL do datasource (`application.yml`); tirar isso quebra as queries de busca.
- `McpPageSupport`: limite default 100, teto 500 nas tools de busca — payload maior estoura a janela de contexto do modelo.
- `SchemaMcpTools.describe_schema`: descreve o schema para o modelo em vez de inchar o system prompt do agent. **Mantenha em sincronia com `V1__init.sql`** ao mudar tabelas/enums.
- `QueryService` / `execute_query`: única exceção ao acesso via JPA (a query é escrita pela LLM em runtime). A blindagem contra escrita é o GRANT no Postgres (role `logistic_ro`, `V2__readonly_role.sql`), não as checagens em Java — essas (`;`, `SELECT`, `LIMIT` implícito de 500) existem só para o modelo se corrigir rápido. Não troque a role por validação em regex.
- `ReadOnlyDataSourceConfig`: declarar um `DataSource` manual desliga a auto-config do principal, então os dois estão declarados ali, o do JPA com `@Primary`. Os beans read-only precisam de `@Qualifier` — sem ele, `@Primary` vence e a conexão "read-only" vira a do JPA (role `postgres`, sem `statement_timeout`).
- Schema por Flyway (`db/migration`), `ddl-auto: validate`. Dados de demo em `db/seed/dados.sql`, aplicados pelo `start.sh` (usa `random()`, então `--reset` gera dataset diferente a cada vez).

### logistic-agent — ponte LLM ↔ MCP

- `ChatClientConfig`: monta o `ChatClient` com o system prompt (em PT-BR, contém a política de escolha de tools e a tradução de status para o usuário), os tool callbacks MCP descobertos da API, o `RenderTool` local e `MessageChatMemoryAdvisor` (janela de 20 mensagens, in-memory — memória some no restart).
- Padrão de render: `RenderTool.renderChart/renderTable` não devolvem dados ao modelo — gravam num `RenderHolder` **request-scoped**, e o `ChatService` lê o holder depois da chamada ao `ChatClient`, devolvendo `{ content, renderData }`. Alterar o escopo do holder vaza render entre requisições concorrentes.
- `RenderableContent` é sealed + `@JsonTypeInfo(property = "type")`; o webui despacha por `renderData.type` (`chart`/`table`). Tipo novo = novo record permitido + `@JsonSubTypes` + branch no `main.js`.
- Ordem de subida importa: o agent faz handshake MCP no startup. Se a API não estiver respondendo `/actuator/health` antes, ele sobe sem as tools e o chat responde "erro ao processar" (`McpServerUnavailableFailureAnalyzer` registra a falha via `META-INF/spring.factories`).

### logistic-webui

`src/main.js` (~230 linhas, sem framework): mantém `sessionId` no `localStorage`, faz `POST` para `VITE_API_URL` (`.env`, default `http://localhost:8080/api/chat`), renderiza markdown com `marked` e despacha `renderData` para `buildChart` (Chart.js) ou `buildTable`.

## Convenções

- Código, nomes de classe e API em inglês; comentários, descrições de `@McpTool`/`@ToolParam` e system prompt em **português**. Descrição de tool é prompt, não documentação: ela é o que faz o modelo escolher a tool certa — inclua valores de enum e um exemplo.
- Enums e status trafegam em inglês (`DELIVERED`, `COMPLETED_WITH_FAILURES`); a tradução para PT-BR é responsabilidade do system prompt na exibição.
- Mensagens de commit em português, Conventional Commits.

## Segurança

A stack é local e sem autenticação por desenho: API aberta em 8081, Postgres com `postgres/postgres` e porta publicada, MCP server sem token com `execute_query` livre, senha da role `logistic_ro` fixa e versionada. Não exponha em rede; não trate esses pontos como bugs a "corrigir" sem o usuário pedir.
