# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Três apps independentes num só repo (não é multi-módulo Maven — cada um tem seu `pom.xml`/`package.json`):

| Diretório | Stack | Porta |
|-----------|-------|-------|
| `logistic-webui/` | Vite 8, Chart.js 4, marked, JS puro (sem framework) | 5173 |
| `logistic-agent/` | Java 21, Spring Boot 4.0.6, Spring AI 2.0.0 (MCP **client**) | 8080 |
| `logistic-api/` | Java 21, Spring Boot 4.0.6, JPA, Flyway, MCP **server** | 8081 |

Postgres 18 em 5432, via `logistic-api/docker-compose.yaml` (container `logisticdb`).
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

Testes da API rodam em H2 (`MODE=PostgreSQL`, perfil `test`) — não precisam de Docker. O schema vem do **Flyway rodando a própria `V1__init.sql`** (`target: 1`, porque a V2 cria role e dá GRANT, sintaxe que o H2 não tem), com `gen_random_uuid()` registrado via `flyway.init-sqls`. `ddl-auto: none` de propósito: tanto `create-drop` quanto `validate` fazem o Hibernate renderizar DDL, e o `H2Dialect` não tem nome de tipo para `NAMED_ENUM` (`SqlTypes code: 6001`), que é o mapeamento de `Route.status` e `Order.status`. O preço é não checar drift entidade x schema; em troca os testes rodam contra a migration real, e drift vira erro de SQL no teste de repositório. `@DataJpaTest` exige `@ActiveProfiles("test")`; testes de controller usam `@WebMvcTest` + `@MockitoBean` no service.

Logs de cada app vão para `logs/logistic-{api,agent,webui}.log` — o terminal do `start.sh` só mostra progresso. Para diagnosticar subida, é `tail` nesses arquivos.

## Arquitetura

### logistic-api — dono do domínio

Camadas: `controller/` (REST) e `mcp/` (tools) são **dois adaptadores sobre o mesmo `service/`**. Nem controller nem tool MCP têm lógica; regra de negócio mora só em `service/`. Assimetria desde a remoção das tools de leitura: o REST expõe busca e escrita, o MCP expõe só escrita (a leitura da LLM vai por `executeQuery`). Caso de uso de escrita novo mexe nos três; caso de uso de leitura novo mexe em service e controller, e para a LLM basta o schema já descrito em `describeSchema`.

- `repository/`: filtro dinâmico via JPQL com `(:param IS NULL OR ...)`. Parâmetros string/uuid/timestamp exigem `CAST(:param AS tipo)` — o Postgres não infere o tipo sozinho. Por isso também o `prepareThreshold=0` na URL do datasource (`application.yml`); tirar isso quebra as queries de busca.
- Teto de payload: `QueryService.MAX_ROWS` (50) aplicado quando a query da LLM não traz `LIMIT`. É o **único** teto desde que as tools tipadas de busca saíram (o `McpPageSupport`, que dava default 25 e teto 100, foi removido junto). Payload maior estoura a janela de contexto e, pior, faz a LLM *gerar* uma linha de tabela por registro no `renderTable` — o gargalo real de latência (o SQL responde em <1ms). Mudou o valor? Ajuste também a descrição do `executeQuery`, que anuncia o limite ao modelo — ela é prompt.
- **`SchemaText` é a fonte do schema para o modelo, e mudança no banco morre aqui se não for propagada.** Migration nova que crie/renomeie/remova tabela, coluna ou enum **tem que** ser refletida em `SchemaText` — senão a LLM continua escrevendo SQL com o schema antigo e as queries falham (ou pior: acertam com a coluna errada). É o mesmo cuidado que existia com `describeSchema`, agora num lugar só. O texto está em constantes (`TABLES`, `ENUMS`, `RELATIONSHIPS`, `QUERY_RULES`, e `FULL` concatenando) porque descrição de `@McpTool` é anotação, e anotação exige constante de compilação — não dá para gerar do `information_schema` nem dos enums em runtime. `SchemaTextTest` cobre só a parte dos enums (status novo sem entrada no texto, ou marca `(finalizador)` divergindo do `isFinal()`); **coluna e tabela ninguém checa por você.**
- `SchemaText.FULL` entra na descrição do `executeQuery`, não só no `describeSchema`: descrição de tool está sempre no contexto, tool precisa ser chamada. Numa rodada do eval, 31 casos usaram `executeQuery` e só 2 chamaram `describeSchema` antes — o modelo escrevia SQL de memória e errava nome de coluna (`"zipCode"` em vez de `zip_code`). Custa ~800 tokens por chamada, no prefixo estável que o cache aproveita. Só se sustenta porque o banco tem 5 tabelas; com schema grande, o caminho é devolver o schema no retorno de erro da query, não inchar a descrição.
- `SchemaMcpTools.describeSchema`: devolve o mesmo `SchemaText.FULL`. Serve à pergunta *sobre* o modelo de dados ("quais status existem?"), não é pré-requisito da consulta — a descrição diz isso para o modelo não gastar um round-trip com ela antes de cada query. O texto traz as regras que o modelo erra sozinho: `driver.name` não é único (filtrar por nome soma homônimos de estados diferentes), agregação por motorista é `GROUP BY d.id, d.name` para o id ficar no contexto do follow-up, falha de entrega é só `order.status='DELIVER_FAILURE'` — combinar com `route.status` subconta em silêncio — e follow-up mantém os filtros do turno anterior.
- Os exemplos de SQL na descrição do `executeQuery` são prompt, e o modelo copia literalmente: um exemplo com `GROUP BY d.name` e filtro de `route.status` fez o chat responder 5 falhas onde havia 19, e depois somar três motoristas homônimos num gráfico só. Ao mexer neles, escreva a query que você quer ver o modelo escrevendo.
- `QueryService` / `executeQuery`: única exceção ao acesso via JPA (a query é escrita pela LLM em runtime). A blindagem contra escrita é o GRANT no Postgres (role `logistic_ro`, `V2__readonly_role.sql`), não as checagens em Java — essas (`;`, `SELECT`, `LIMIT` implícito de 50) existem só para o modelo se corrigir rápido. Não troque a role por validação em regex.
- `ReadOnlyDataSourceConfig`: declarar um `DataSource` manual desliga a auto-config do principal, então os dois estão declarados ali, o do JPA com `@Primary`. Os beans read-only precisam de `@Qualifier` — sem ele, `@Primary` vence e a conexão "read-only" vira a do JPA (role `postgres`, sem `statement_timeout`).
- Schema por Flyway em **duas pastas**: `db/migration` (schema puro, roda também nos testes em H2) e `db/roles` (a V2, com `CREATE ROLE`/`GRANT`, que só existe no Postgres). `ddl-auto: validate`. Migration que mexa em tabela, coluna ou enum **exige atualizar `SchemaText`** no mesmo commit — ver a seção do `SchemaText` acima; o teste só pega enum, coluna passa batido. Dados de demo em `db/seed/dados.sql`, aplicados pelo `start.sh` (usa `random()`, então `--reset` gera dataset diferente a cada vez).

### logistic-agent — ponte LLM ↔ MCP

- `ChatClientConfig`: monta o `ChatClient` com o system prompt (em PT-BR, contém a regra de leitura só por `executeQuery` e a tradução de status para o usuário), os tool callbacks MCP descobertos da API, o `RenderTool` local e `MessageChatMemoryAdvisor` (janela de 20 mensagens, in-memory — memória some no restart).
- Reasoning desligado (`spring.ai.openai.chat.options.extra-body` → `chat_template_kwargs.enable_thinking=false`): o `llama-server` sobe com `--jinja` e o template do Qwen3.6 liga o thinking por padrão, gastando centenas de tokens de `<think>` por chamada — e são 2+ chamadas por pergunta (uma por rodada de tool). As chaves vão entre colchetes no YAML para o binder não normalizar o underscore; `ChatOptionsBindingTest` guarda isso, porque a falha é silenciosa (o servidor ignora chave desconhecida e o thinking volta).
- Timeout da LLM (`llmTimeoutCustomizer`, 300s de read): a chamada **não é streaming**, então a LLM local não devolve byte nenhum até terminar de gerar — o read timeout tem que cobrir a geração inteira. Curto demais e o okhttp fecha o socket no meio (`SocketException: Socket closed`), e o chat mostra "erro ao processar". O webui aborta em 310s (`REQUEST_TIMEOUT_MS`), logo acima — mudou um, mude o outro.
- Padrão de render: `RenderTool.renderChart/renderTable` não devolvem dados ao modelo — gravam num `RenderHolder` **request-scoped**, e o `ChatService` lê o holder depois da chamada ao `ChatClient`, devolvendo `{ content, renderData }`. Alterar o escopo do holder vaza render entre requisições concorrentes.
- `RenderTool` valida os argumentos do modelo (labels/datasets/columns/rows não vazios, e `data.size() == labels.size()`, `row.size() == columns.size()`) e devolve a crítica como **retorno da tool**, sem gravar no holder — o modelo lê e refaz a chamada. Retorno de tool é feedback, não log: uma tool que diz "preparado" para argumentos quebrados faz o modelo afirmar ao usuário que o gráfico ficou pronto. A crítica também fica no `RenderHolder` (`registerRejection`), e o `ChatService` anexa um aviso ao texto quando a resposta sai sem `renderData` depois de uma recusa — o modelo às vezes ignora a crítica e anuncia o gráfico mesmo assim, e o prompt sozinho não garante o contrário. Uma chamada bem-sucedida limpa o erro. O `main.js` ainda envolve o dispatch de `renderData` em try/catch, porque os dados vêm da LLM.
- Render só quando o usuário pede (`ChatService.VISUAL_REQUEST` → `RenderHolder.setRenderAllowed`, checado em `RenderTool.policyRefusal`): a tool não vê a pergunta, então quem decide é o `ChatService` a partir da mensagem. Sem isso o modelo desenhava gráfico por conta própria numa pergunta analítica ("qual a taxa de falha por estado?") — texto é o padrão e o prompt manda **oferecer** a visualização. Com render bloqueado o retry corretivo também não roda, senão a própria oferta ("posso mostrar em gráfico") disparava um round-trip extra. Palavra nova no regex = caso novo no `tool-selection.json`; os follow-ups ("refaça em barras", "transforme isso num gráfico") só passam porque o termo aparece na mensagem. O "sim" à oferta não traz termo nenhum, então o `ChatService` guarda a oferta pendente por sessão (`pendingVisualOffer`, gravada quando a resposta menciona visualização sem desenhar) e a consome no aceite (`AFFIRMATIVE`), uma vez só. E as recusas de política **cedem no teto** (`RenderTool.yielding`): a segunda chamada insistente passa e desenha. Sem isso o modelo determinístico reenvia a mesma chamada para sempre — o bloqueio sozinho rodou 182 recusas idênticas numa pergunta real, mesma armadilha da recusa por argumento inválido: só retorno de sucesso encerra o loop de tool calls.
- Tradução de status no render (`RenderTool.STATUS_PT`): as células de `renderTable` e os rótulos de `renderChart` passam por um mapa EN→PT antes de ir para o holder. O modelo traduzia o texto da resposta e copiava o enum cru para o payload, então a tela mostrava "Entregue" no parágrafo e "DELIVERED" na tabela. Tradução de enum é determinística — é código, não instrução; o prompt guarda só a tradução do texto e avisa que nos argumentos de render o enum pode ir cru. Enum novo = entrada nova no mapa, no system prompt e no `SchemaMcpTools`.
- Tabela markdown duplicada (`ChatService.withoutDuplicatedTable`): com `renderData` na resposta, as linhas `| ... |` do texto são removidas — o modelo repetia no markdown os mesmos dados do gráfico, e o prompt sozinho não segurava.
- Uma visualização por resposta (`RenderTool`): a segunda chamada de render na mesma requisição é recusada. O `RenderHolder` guarda um conteúdo só, então uma segunda chamada bem-sucedida sobrescrevia a primeira em silêncio — o modelo desenhava tabela *e* gráfico para "taxa de falha por estado", o usuário via só a última e o texto anunciava as duas. Render sem pedido explícito também saiu do prompt e das descrições das tools: texto é o padrão, e visualização só quando o usuário pede (ou quando ele aceita a oferta).
- Dado inventado sem tool (`ToolCallHolder` + `ChatService.answeredWithoutData`): resposta com dígito, nenhuma tool chamada no turno e nenhum render produzido → refaz com instrução corretiva, até duas vezes. O `ToolCallHolder` é request-scoped e é alimentado pelo `ToolCallLoggingConfig`, que já interceptava toda tool call para o log. Existe porque **fora do primeiro turno da sessão o modelo responde de memória**: "e em MG?" depois de "pedidos entregues em SP" devolveu 106 onde havia 423, e uma listagem de MG com cidade de SP dentro — log de tool calls vazio nos dois. O system prompt já proíbe isso explicitamente e o modelo ignora; o gatilho aqui não é heurística sobre a pergunta, é fato binário do turno. As três condições importam: sem o teste de dígito, recusa ("não suportamos exclusão") e saudação disparariam; sem o teste de render, "transforme isso num gráfico" — que legitimamente reaproveita dados do turno anterior — disparava. Numa rodada completa do eval não disparou nenhuma vez: custo zero quando o modelo se comporta.
- Retry corretivo (`ChatService`): se a resposta menciona gráfico/tabela/pizza e o `RenderHolder` está vazio, o `ChatService` refaz a chamada ao `ChatClient` com uma instrução corretiva (mesma sessão, então o modelo mantém o contexto) e devolve o resultado dela — até **duas** tentativas, a segunda mais dura (a primeira, branda, recupera a maior parte, mas não todas). Existe porque o modelo às vezes responde "aqui está o gráfico de pizza" sem chamar tool nenhuma — nem a de busca, nem a de render — e o log de tool calls fica vazio naquele turno. Custa um round-trip a mais só no caminho de falha.
- Teto de recusas de render (`MAX_REJECTIONS = 2`, contado no `RenderHolder` por requisição): a crítica devolvida pela tool é o que faz o modelo se corrigir, mas com temperatura baixa ele reenvia a **mesma** chamada, e o loop de tool calls do Spring AI 2.0 não tem limite de rodadas — uma requisição do eval (temperatura 0) rodou 172 recusas idênticas em 26 minutos até estourar o contexto de 260k. Na última tentativa a tool para de pedir correção e **desenha assim mesmo**: gráfico truncado no menor tamanho comum entre labels e data, tabela com linhas cortadas ou completadas com `-`. Encerrar o loop tem que vir de um retorno de sucesso — mensagem pedindo para o modelo parar não garante nada com modelo determinístico. O retorno manda avisar o usuário de que a visualização saiu parcial.
- `ToolCallLoggingConfig`: loga toda tool call (nome, args, retorno truncado) via `ObservationHandler`. É o que distingue "a tool falhou" de "o modelo disse que fez sem chamar a tool" — sem isso as duas viram a mesma frase na tela. Independente do Langfuse, que é opcional.
- **Leitura é só `executeQuery`.** Das 10 tools, uma lê (`executeQuery`), uma descreve o schema (`describeSchema`) e oito escrevem (create/update/link/assign). Não existem tools tipadas de busca ou contagem: elas foram removidas porque `executeQuery` já respondia tudo que elas respondiam, e a sobreposição fazia o modelo escolher errado — um terço do `tool-selection.json` existia só para policiar essa escolha. E porque elas *não* alcançavam perguntas compostas: "o motorista com mais falhas por estado" é argmax por grupo, que nenhuma delas expressa e que o modelo, sem caminho de tool, respondia inventando dados. Autorização por linha (usuário que vê um estado e não outro) fica com RLS no Postgres, não com filtro em parâmetro de tool.
- **Não existe tool de remoção/exclusão**, e `executeQuery` só aceita SELECT. O system prompt manda dizer que exclusão não é suportada; sem essa instrução o modelo inventava motivo ("veículo vinculado a motoristas") para uma operação que nunca existiu.
- Eval (`./mvnw test -Peval` no agent, exige API e LLM no ar): o dataset inteiro custa caro — cada caso é ao menos uma ida à LLM, em série, e os com `setup` são duas. Ao mexer numa regra, rode o recorte: `-Deval.cases=driver-followup-filters-by-id,driver-failures-ignore-route-status`. O piso (`-Deval.threshold`) passa a valer sobre o subconjunto, então recorte é para iterar, não para aprovar mudança.
- Antes de escrever regra nova no system prompt, pergunte se o código pode garantir aquilo. Limite de payload é teto no `QueryService`, não pedido ao modelo; argumento de render inválido é validação na tool, não instrução. O prompt fica com o que só ele carrega: fatos do domínio (tradução de status, ausência de exclusão, leitura só por `executeQuery`) e comportamento que nenhum código alcança (não confirmar ação sem retorno de tool). E o que ficar precisa de caso no `tool-selection.json` — regra de prompt sem eval é regra que ninguém percebe quando para de valer, ainda mais depois de trocar de modelo.
- Sessão: o `main.js` gera um `sessionId` **novo a cada carregamento da página**. As mensagens vivem só no DOM e somem no F5, enquanto a `ChatMemory` do agent é indexada pelo `sessionId` e não some — reaproveitar o id fazia o modelo responder sobre uma conversa que já não estava na tela. Se um dia o histórico for persistido no `localStorage`, aí sim o id volta a ser reaproveitável.
- `RenderableContent` é sealed + `@JsonTypeInfo(property = "type")`; o webui despacha por `renderData.type` (`chart`/`table`). Tipo novo = novo record permitido + `@JsonSubTypes` + branch no `main.js`.
- Observabilidade (`LangfuseObservabilityConfig`): opcional, atrás da flag `langfuse.enabled`
  (`LANGFUSE_ENABLED`, **default `false`**) — ela liga o `management.tracing.enabled` e é a condição
  da própria `@Configuration`. Traces OTLP para o Langfuse (`http://localhost:8060`, stack comentada
  em `logistic-agent/docker-compose.yaml`, subido à mão, fora do `start.sh`; provisiona projeto e
  chaves via `LANGFUSE_INIT_*`, e o `ENCRYPTION_KEY` precisa de aspas ou o YAML lê como número), autenticados pelo header `Basic ${LANGFUSE_AUTH}` — base64
  de `public:secret`, derivado pelo `start.sh` a partir do `logistic-agent/.env` (mesma convenção do
  `logistic-webui/.env`: `.env` ignorado, `.env.example` versionado). Prompt, resposta e argumentos/retorno de tool viram atributos de span via
  **`ObservationFilter`**, não `ObservationHandler`: no `onStop` do handler a span já foi encerrada pelo
  tracing e as tags se perdem. As propriedades `spring.ai.chat.observations.log-*` só escrevem no log da
  aplicação — não alimentam o Langfuse. O `ObservationPredicate` corta health checks por **dois**
  caminhos, e ambos importam: a requisição HTTP que chega (`/actuator/**` e `/api/chat/health`, senão o
  polling do `start.sh` e do webui gera um trace por segundo) e o `@Scheduled` do
  `BackendHealthIndicator`, que o Spring observa sozinho como `tasks.scheduled.execution` e rendia um
  trace de 5ms a cada 15s. `LangfuseObservabilityConfigTest` cobre os dois. Com a flag desligada não há Tracer no contexto — nada aqui
  carrega, e o `ChatService` (que taga a span com `sessionId` e input/output do trace) vira no-op.
- Ordem de subida importa: o agent faz handshake MCP no startup. Se a API não estiver respondendo `/actuator/health` antes, ele sobe sem as tools e o chat responde "erro ao processar" (`McpServerUnavailableFailureAnalyzer` registra a falha via `META-INF/spring.factories`).

### logistic-webui

`src/main.js` (~230 linhas, sem framework): mantém `sessionId` no `localStorage`, faz `POST` para `VITE_API_URL` (`.env`, default `http://localhost:8080/api/chat`), renderiza markdown com `marked` e despacha `renderData` para `buildChart` (Chart.js) ou `buildTable`.

## Convenções

- Código, nomes de classe e API em inglês; comentários, descrições de `@McpTool`/`@ToolParam` e system prompt em **português**. Descrição de tool é prompt, não documentação: ela é o que faz o modelo escolher a tool certa — inclua valores de enum e um exemplo.
- Enums e status trafegam em inglês (`DELIVERED`, `COMPLETED_WITH_FAILURES`); a tradução para PT-BR é responsabilidade do system prompt na exibição.
- Mensagens de commit em português, Conventional Commits.

## Segurança

A stack é local e sem autenticação por desenho: API aberta em 8081, Postgres com `postgres/postgres` e porta publicada, MCP server sem token com `executeQuery` livre, senha da role `logistic_ro` fixa e versionada. Não exponha em rede; não trate esses pontos como bugs a "corrigir" sem o usuário pedir.
