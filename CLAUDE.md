# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Três apps independentes num só repo (não é multi-módulo Maven — cada um tem seu `pom.xml`/`package.json`):

| Diretório | Stack | Porta |
|-----------|-------|-------|
| `logistic-webui/` | Vite 8, Chart.js 4, marked, JS puro (sem framework) | 5173 |
| `logistic-agent/` | Java 21, Spring Boot 4.0.6, Spring AI 2.0.0 (MCP **client**) | 8080 |
| `logistic-api/` | Java 21, Spring Boot 4.0.6, JPA, Flyway, MCP **server** | 8081 |
| Keycloak (`quay.io/keycloak/keycloak:26.7`, realm `logistic`) | Autenticação/autorização OAuth2 | 8090 |

Postgres 18 em 5432, via `docker-compose.yaml` na raiz (container `logisticdb`, serviço `postgres` sem profile — sobe por padrão).
LLM local OpenAI-compatível esperada em `http://localhost:8200` (`qwen3.6:35b`) — opcional para subir a stack, obrigatória para o chat responder. Esses dois valores são o default em `application.yml` (`${LLM_BASE_URL:...}`/`${LLM_MODEL:...}`); no `.env` da raiz (gitignored, carregado pelo `start.sh`) dá para sobrescrever para outra máquina/host sem rebuild depois da primeira vez.

Regra estruturante: **a LLM nunca toca o banco.** Ela chama tools MCP expostas pela `logistic-api`; só a API executa SQL. O `logistic-agent` não tem datasource nem dependência de banco no `pom.xml` — se aparecer a necessidade de dados lá, a resposta é uma tool MCP nova na API, não um repositório no agent.

## Comandos

Stack inteira (sobe Postgres → Keycloak (realm importado) → API → seed → agent → webui, na ordem, com espera entre etapas):

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
- **Escrita passa por confirmação do usuário** (`confirm/ConfirmingToolCallbackProvider`): o decorator embrulha as tools MCP e as de escrita **não executam** quando o modelo chama — elas registram uma `PendingAction` (holder request-scoped + `PendingActionStore` de aplicação) e devolvem "aguardando confirmação". O `ChatService` põe a pendência no `ChatMessageDTO`, o webui desenha o card com Confirmar/Cancelar, e o `POST /api/chat/confirm` executa o `ToolCallback` original **sem passar pela LLM**, com o JSON de argumentos registrado. Detalhes que não são acessórios:
  - A lista é **por exclusão**: leitura é `executeQuery` e `describeSchema`, o resto é escrita. Tool nova nasce confirmada; o inverso deixaria uma tool nova gravando sozinha até alguém lembrar de atualizar a classe.
  - **Campo obrigatório faltando não vira pendência** (`RequiredArgumentsCheck`): a lista de obrigatórios sai do `required` do próprio `inputSchema` da tool, então tool nova é coberta sozinha — nada é declarado no agent. Valor de "não sei" preenchido pelo modelo (`N/A`, `-`, `null`, `não informado`) conta como ausência, senão vira texto literal no banco. As descrições das tools de escrita na API listam os obrigatórios e mandam perguntar antes de chamar — descrição é prompt, e é ela que evita o round-trip da recusa. Deixar a API recusar não serve: ela recusa **depois** da confirmação, e o usuário já teria clicado em confirmar num card com "Nome: -". O que isso *não* pega é o valor **inventado** (`joao@email.com` para "cadastre o motorista João") — nenhum schema distingue isso de dado real; quem pega é o próprio card, que mostra cada valor antes de gravar.
  - **Replay literal.** Nunca peça ao modelo para refazer a chamada depois do "sim": com o payload reescrito, o usuário confirma uma coisa e outra é gravada. Pela mesma razão a frase do card é montada em código (`PendingActionMapper`), não pedida à LLM.
  - **Ação anunciada sem tool chamada** (`ChatService.ACTION_CLAIM` + `ACTION_CORRECTIONS`): resposta que afirma "aguardando sua confirmação" — ou que dá a gravação por feita ("cadastrado com sucesso", "cadastrei") — com o `PendingActionHolder` vazio dispara retry corretivo, até duas vezes, e no fim a tela desmente (`withUnregisteredActionNotice`). Mesma patologia do "aqui está o gráfico" sem `renderChart`, e aconteceu no primeiro teste real: "Adicione um novo motorista João Ribeiro" + os dados no turno seguinte, log de tool calls **vazio** nos dois turnos, e a tela com a frase de confirmação sem botão nenhum. O `answeredWithoutData` não pega esse caso porque a frase não tem dígito. A parte de "aguardando confirmação" é restrita a frases que afirmam a **existência** da pendência ("preciso do e-mail para registrar a ação" é o modelo pedindo dado e não pode virar retry); a de conclusão exige o marcador de sucesso, porque "o motorista foi cadastrado em 12/03/2024" é leitura legítima de `created_at`.
  - **Pedido de escrita que não virou pendência** (`ChatService.WRITE_REQUEST` + `writeWentNowhere`): o gatilho aqui **não é a frase do modelo**, é o pedido do usuário (regex sobre a mensagem, como o `VISUAL_REQUEST`) mais a ausência de pendência no fim do turno. Nasceu do usuário sem a role `write`: sem as tools de escrita na lista, o modelo tenta contornar por `executeQuery` (o `INSERT` morre na role read-only) e anuncia sucesso — e em três execuções da **mesma** pergunta ele disse "cadastrado com sucesso", "a ação foi registrada" e "será cadastrado assim que você confirmar na tela". Perseguir frase não fecha isso; o que fecha é o fato. O aceite curto ("sim, pode cadastrar", ou só "sim") mantém o pedido de pé por conversa (`pendingWriteIntent`, mesmo mecanismo do `pendingVisualOffer`) — é justamente no turno do aceite que o modelo dá por feito. Duas saídas para não gastar o aviso à toa: resposta terminada em `?` (falta dado, fluxo aberto) e `DENIAL` (a resposta já diz que não deu). O `DENIAL` é **supressão, não detecção**: se ele falhar, sobra um aviso redundante; nunca esconde mentira, porque o `ACTION_CLAIM` é avaliado antes e tem precedência.
  - **Nada verificado, depois das correções** (`withUnverifiedAnswerNotice`): quando o `answeredWithoutData` continua valendo depois dos dois retries, o número inventado ia para a tela sem ressalva nenhuma. Agora a tela diz que nenhuma tool foi chamada. Os três avisos são exclusivos entre si, do mais específico para o mais genérico — dois "isso não aconteceu" na mesma resposta viram ruído, e ruído faz o usuário parar de ler o aviso que importa.
  - **O aviso de "nada foi gravado ainda" é incondicional** (`ChatService.withPendingActionNotice`) enquanto houver pendência. O modelo escreve "cadastrado com sucesso" diante de qualquer retorno positivo, e procurar essa afirmação na resposta é heurística perdida — há infinitas formas de dizer que fez.
  - **Uma escrita por resposta.** A segunda chamada de escrita é recusada; a repetição da **mesma** chamada devolve a **mesma** pendência, com cara de sucesso. É o retorno de sucesso que encerra o loop de tool calls (a armadilha das 182 recusas do render), e aqui não dá para "ceder e executar" no teto — ceder é o que a confirmação existe para impedir.
  - O desfecho (confirmado/cancelado/falhou) entra na `ChatMemory` da sessão, porque o modelo não participa desse passo e sem isso o turno seguinte responderia sobre uma ação eternamente pendente. O `PendingActionStore` consome a pendência **uma vez** (dois cliques = duas escritas, e nenhuma tool da API é idempotente), com TTL de 15min e teto de 200.
  - **Não é autorização.** É um gate de UX para a LLM, não controle de acesso — a autenticação de verdade é a do Keycloak (ver seção *Segurança*), que continua valendo por baixo: quem chama a API em 8081 direto ainda precisa de um token com a role certa, e um usuário com `write` que confirmasse fora da tela também passaria. O que isto impede é a LLM gravando por conta própria a partir de uma frase ambígua, não uma pessoa autorizada.
  - Fora de requisição HTTP não há holder e a escrita executa direto (log em WARN). Isso vale para contexto sem servlet; o eval **tem** `MockHttpServletRequest`, então lá a escrita também vira pendência — por isso o recorder do eval passou a ser `ObservationHandler` (`EvalTestConfig`) em vez de decorator de `ToolCallbackProvider`: por dentro da confirmação, um decorator nunca seria chamado e o eval veria "nenhuma tool chamada".
- **Leitura é só `executeQuery`.** Das 12 tools, uma lê (`executeQuery`), uma descreve o schema (`describeSchema`) e dez escrevem (create/update/link/assign/**delete**). Não existem tools tipadas de busca ou contagem: elas foram removidas porque `executeQuery` já respondia tudo que elas respondiam, e a sobreposição fazia o modelo escolher errado — um terço do `tool-selection.json` existia só para policiar essa escolha. E porque elas *não* alcançavam perguntas compostas: "o motorista com mais falhas por estado" é argmax por grupo, que nenhuma delas expressa e que o modelo, sem caminho de tool, respondia inventando dados. Autorização por linha (usuário que vê um estado e não outro) fica com RLS no Postgres, não com filtro em parâmetro de tool.
- **Exclusão existe só para motorista e veículo** (`deleteDriver`, `deleteVehicle`), sempre por id — o modelo consulta com `executeQuery` e usa o id retornado; a descrição da tool proíbe UUID inventado. Pedido e rota continuam sem exclusão, e `executeQuery` só aceita SELECT: aí o system prompt segue mandando dizer que não é suportado, porque sem a instrução o modelo inventava motivo ("veículo vinculado a motoristas") para uma operação que não existe. Regras que moram no service, não no prompt: motorista **com rotas** é recusado com `ConflictException` (a FK `route→driver` é `ON DELETE RESTRICT`, e deixar o banco estourar devolveria erro de constraint no lugar de explicação), e o `DeletionSummary` conta os vínculos `driver_vehicle` que caíram por `CASCADE` — exclusão que apaga três vínculos em silêncio é o efeito colateral que o usuário precisa ver. Human in the loop vem de graça: o `ConfirmingToolCallbackProvider` classifica por exclusão, então `delete*` nasceu confirmada sem tocar no agent; o `PendingActionMapper` só marca `destructive` (derivado do prefixo `delete`) para o webui pintar o card e o botão de vermelho e escrever "Excluir". E o card de exclusão mostra o **registro**, não o UUID: o `DeletionTargetLookup` roda um SELECT fixo pela própria tool `executeQuery` (chamada pelo agent, sem LLM no meio) e guarda os campos no `PendingAction.details`. Confirmar um UUID não é conferir nada — ainda mais com `driver.name` não sendo único, onde o modelo pode ter escolhido o homônimo errado. Id que não existe (UUID inventado, registro já removido) é **recusado antes do card**, com instrução de consultar primeiro: a alternativa era o usuário clicar em confirmar e só então receber "não encontrado".
- Eval (`./mvnw test -Peval` no agent, exige API e LLM no ar): o dataset inteiro custa caro — cada caso é ao menos uma ida à LLM, em série, e os com `setup` são duas. Ao mexer numa regra, rode o recorte: `-Deval.cases=driver-followup-filters-by-id,driver-failures-ignore-route-status`. O piso (`-Deval.threshold`) passa a valer sobre o subconjunto, então recorte é para iterar, não para aprovar mudança.
- Antes de escrever regra nova no system prompt, pergunte se o código pode garantir aquilo. Limite de payload é teto no `QueryService`, não pedido ao modelo; argumento de render inválido é validação na tool, não instrução. O prompt fica com o que só ele carrega: fatos do domínio (tradução de status, ausência de exclusão, leitura só por `executeQuery`) e comportamento que nenhum código alcança (não confirmar ação sem retorno de tool). E o que ficar precisa de caso no `tool-selection.json` — regra de prompt sem eval é regra que ninguém percebe quando para de valer, ainda mais depois de trocar de modelo.
- Sessão: o `main.js` gera um `sessionId` **novo a cada carregamento da página**. As mensagens vivem só no DOM e somem no F5, enquanto a `ChatMemory` do agent não some — reaproveitar o id fazia o modelo responder sobre uma conversa que já não estava na tela. Desde a fase 5 a chave real da conversa não é o `sessionId` sozinho: é `AuthenticatedUser.conversationId(sessionId)`, que combina o `sub` do JWT autenticado com o `sessionId` recebido. Com autenticação, o `sessionId` deixou de ser exclusivo de quem o gerou — o webui não muda de sessionStorage por usuário, e nada impede alguém de forçar o mesmo valor de outra sessão —, então mandar o `sessionId` de outra pessoa não pode mais ler a conversa dela nem resgatar a pendência dela (`PendingActionStore`, `pendingVisualOffer`). Sem `sub` (fora de requisição HTTP autenticada), a chave cai para o `sessionId` cru — hoje só acontece antes do eval se autenticar, ou em teste que não monta `SecurityContext`. Se um dia o histórico for persistido no `localStorage`, o `sessionId` volta a ser reaproveitável entre cargas de página, mas o isolamento por usuário continua valendo do mesmo jeito.
- `RenderableContent` é sealed + `@JsonTypeInfo(property = "type")`; o webui despacha por `renderData.type` (`chart`/`table`). Tipo novo = novo record permitido + `@JsonSubTypes` + branch no `main.js`.
- Observabilidade (`LangfuseObservabilityConfig`): opcional, atrás da flag `langfuse.enabled`
  (`LANGFUSE_CLIENT_ENABLED`, **default `false`**) — ela liga o `management.tracing.enabled` e é a condição
  da própria `@Configuration`. Independente do `LANGFUSE_SERVER_ENABLED`, que só controla se o
  `docker-compose.yaml` da raiz sobe os containers do Langfuse (sob o profile `langfuse`,
  `docker compose --profile langfuse up -d`) — dá pra ter o servidor rodando em outra máquina e
  só o client (esta flag) ligado aqui. Traces OTLP para o Langfuse (`http://localhost:8060`, stack
  subida à mão, fora do `start.sh`; provisiona projeto e
  chaves via `LANGFUSE_INIT_*`, e o `ENCRYPTION_KEY` precisa de aspas ou o YAML lê como número), autenticados pelo header `Basic ${LANGFUSE_AUTH}` — base64
  de `public:secret`, derivado pelo `start.sh` a partir do `.env` da raiz — um arquivo só para LLM,
  webui e Langfuse (`.env` ignorado, `.env.example` versionado); o Vite do webui lê o mesmo arquivo
  via `envDir` em `vite.config.js`, expondo ao bundle só as chaves prefixadas `VITE_`. Prompt, resposta e argumentos/retorno de tool viram atributos de span via
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

`src/main.js` (~320 linhas, sem framework): mantém `sessionId` no `localStorage`, faz `POST` para `VITE_API_URL` (`.env`, default `http://localhost:8080/api/chat`), renderiza markdown com `marked` e despacha `renderData` para `buildChart` (Chart.js) ou `buildTable`. `pendingAction` na resposta vira o card de confirmação (`buildPendingAction`): os botões desabilitam **antes** do `await` — o `PendingActionStore` do agent consome a pendência uma vez só, e o segundo clique voltaria como "ação não encontrada".

## Convenções

- Código, nomes de classe e API em inglês; comentários, descrições de `@McpTool`/`@ToolParam` e system prompt em **português**. Descrição de tool é prompt, não documentação: ela é o que faz o modelo escolher a tool certa — inclua valores de enum e um exemplo.
- Enums e status trafegam em inglês (`DELIVERED`, `COMPLETED_WITH_FAILURES`); a tradução para PT-BR é responsabilidade do system prompt na exibição.
- Mensagens de commit em português, Conventional Commits.

## Segurança

Autenticação e autorização via Keycloak (`infra/keycloak/import/logistic-realm.json`, importado no
primeiro boot do container — mudou o JSON depois de o Keycloak já ter subido? `docker compose down -v`
e suba de novo, ou edite pela UI: `--import-realm` só lê o volume na primeira vez). Quatro usuários,
senha igual ao username: `admin` (role `admin`), `user1` (`chat, read, write`), `user2` (`chat, read`),
`eval-user` (`admin`, usado só pelo arnês do eval — ver mais abaixo). `admin` é role **composta**
(`chat`+`read`+`write`) — é por isso que nenhuma regra em Java menciona `"admin"`: o Keycloak já expande
a composta em `realm_access.roles` no token, e quem tem `admin` passa em qualquer `hasRole` sem código
extra.

Postgres (`postgres/postgres`) e a role `logistic_ro` continuam com senha fixa e versionada — ambiente
local, não exponha em rede. O que mudou é o resto: as três aplicações exigem token, e `executeQuery`
não é mais alcançável sem ele.

**Fluxo de tokens:**

```
                    Keycloak (:8090, realm logistic)
                          ^        ^
       Authorization Code |        | Token Exchange (RFC 8693)
             + PKCE       |        |
                          |        |
  browser ---Bearer(aud=logistic-agent)---> logistic-agent (:8080)
  (webui :5173)                                    |
                                                    |  Bearer(aud=logistic-api)
                                                    v
                                             logistic-api (:8081)
                                               REST + /mcp
```

`logistic-webui` é SPA pública com PKCE (`src/auth.js`, ~300 linhas, sem lib — decisão de escopo, a
alternativa aceitável se isso ficar caro é `oidc-client-ts`). `logistic-agent` é confidencial: resource
server do token do browser **e** cliente OAuth que troca esse token por um com `aud=logistic-api`
(`TokenExchangeService`, RFC 8693) antes de chamar o `/mcp` da API. `logistic-api` é resource server
puro, sem login nenhum.

- **Por que token exchange, e não repassar o token do browser para o `/mcp`.** A spec de autorização
  do MCP é normativa: *"MCP servers MUST NOT accept or transit any other tokens"* — repassar o token
  com `aud=logistic-agent` para a API seria *token passthrough*, o vetor do confused deputy. O agent
  troca o token do usuário por um com `aud=logistic-api`, preservando a identidade (`sub`) mas
  restringindo a audiência. `TokenExchangeService` documenta uma pegadinha de configuração: o Keycloak
  só coloca `logistic-api` no `aud` do token trocado se o client **requisitante** (`logistic-agent`)
  tiver o protocol mapper de audiência — não o alvo (`logistic-api`). O mesmo vale para qualquer client
  novo que precise de um token com audiência específica, incluindo o `logistic-eval` do arnês do eval
  (ver mais abaixo): sem o mapper, o Keycloak não inclui `aud` nenhum por padrão (não existe default),
  e o `JwtDecoder`, que exige `aud=logistic-agent`, rejeita antes de qualquer coisa rodar.

- **Por que a autorização das tools MCP usa `McpTransportContext` como parâmetro do método, e não
  `@PreAuthorize`.** Verificado no bytecode do `mcp-core-2.0.0`:
  `McpServerFeatures$AsyncToolSpecification` roda a tool com
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — numa thread do pool, não na
  thread do servlet. O `SecurityContextHolder`, que é `ThreadLocal`, está vazio ali: `@PreAuthorize`
  negaria **tudo**, inclusive para o `admin`, e a falha não aparece em teste de service nem em
  `@WebMvcTest` — só com a stack de pé. `McpTransportContext` viaja pelo Reactor Context, que não
  depende de thread, e por isso funciona: um extractor
  (`McpTransportConfig.mcpAuthContextExtractor`, na API) copia o header `Authorization` para o
  contexto de transporte na entrada da requisição HTTP, e `McpAuthorization.require(ctx, "write")` lê
  esse header dentro da tool, decodifica com o `JwtDecoder` já configurado (o mesmo validador de
  `aud=logistic-api` — é essa validação que impede passthrough) e confere a role. A auto-config do
  Spring AI (`McpServerStreamableHttpWebMvcAutoConfiguration`) não aceita um `McpTransportContextExtractor`
  externo, então `McpTransportConfig` reconstrói o `WebMvcStreamableServerTransportProvider` à mão
  (mesmos `jsonMapper`/`mcpEndpoint`/`keepAliveInterval`/`disallowDelete` que a auto-config usaria) só
  para plugar o extractor — ela declara o bean dela com `@ConditionalOnMissingBean`, então este a
  substitui sem precisar excluí-la.
  `Hooks.enableAutomaticContextPropagation()` foi considerado e descartado: funcionaria
  (`spring-security-core` registra o accessor via `META-INF/services`), mas é uma linha global e
  mágica — o parâmetro explícito é legível e testável sem subir stack nenhuma.

- **`McpTransportContext` não aparece no `inputSchema` da tool** (verificado em `tools/list` contra a
  API real: `deleteDriver` continua só com `id`) — o `McpJsonSchemaGenerator` exclui explicitamente
  esse tipo (e `McpSyncRequestContext`/`McpAsyncRequestContext`/`McpMeta`) do schema gerado. Não é
  prompt, o modelo nunca tenta preenchê-lo, e por isso o parâmetro pôde entrar em todas as 12 tools
  sem mexer numa descrição sequer.

- **A recusa de permissão NÃO vira HTTP 403.** Era o plano original, mas verificado com `curl` direto
  no `/mcp` (sem nada no meio): o `WebMvcStreamableServerTransportProvider` despacha toda chamada de
  tool — a recusa inclusive — via `ServerResponse.sse(consumer, Duration.ZERO)`, que já comitou HTTP
  200 antes de a tool rodar. Uma exceção dentro da tool vira `CallToolResult.isError(true)`, entregue
  como texto dentro do 200/SSE já aberto — nunca um status alternativo, não importa o que for lançado.
  (O único ponto deste SDK que produz um status HTTP diferente é o `ServerTransportSecurityValidator`,
  uma checagem de `Origin`/`Host` que roda **antes** de o corpo ser parseado — sem visibilidade de qual
  tool está sendo chamada, não dá para checar role ali.) A solução: `McpAuthorizationException`
  (`logistic-api`) grava um marcador estável (`insufficient_scope`) na mensagem; no `logistic-agent`, um
  `ToolExecutionExceptionProcessor` customizado (`ChatClientConfig`, sobrescrevendo o
  `@ConditionalOnMissingBean` padrão) procura esse marcador e só ali deixa a `ToolExecutionException`
  propagar — todo o resto cai no comportamento padrão do Spring AI (erro vira texto de volta ao
  modelo). Isso importa por um motivo específico deste repositório: só retorno de sucesso encerra o
  loop de tool calls (ver a armadilha das 172 recusas idênticas de render, mais abaixo na seção do
  agent) — se a recusa de permissão caísse no caminho padrão (texto ao modelo), o modelo determinístico
  reenviaria a mesma chamada negada para sempre. `ChatService.respond` captura essa exceção específica
  e devolve "Você não tem permissão para executar essa operação.", em vez de deixá-la virar um 500. As
  requisições REST de verdade (`POST /api/chat/confirm` no agent, `/api/**` na API) **não** têm esse
  problema — são endpoints HTTP normais atrás do `SecurityFilterChain`, e `user2` sem `write` recebe um
  403 real ali, sem marcador nenhum envolvido.

- **Por que `/mcp` é `permitAll`** nos dois `SecurityFilterChain` (agent e API): o agent faz o handshake
  MCP (`initialize`/`tools/list`) no **startup**, fora de qualquer requisição HTTP e sem usuário nenhum.
  Exigir autenticação ali faria o agent subir sem tools e o chat responder "erro ao processar"
  (`McpServerUnavailableFailureAnalyzer`). A autorização de verdade mora dentro de cada tool
  (`McpAuthorization.require`): sem token, o chamador é anônimo e toda tool nega — permitir a conexão
  não é permitir a chamada.

- **Timeout de 5 minutos é `SSO Session Idle` no Keycloak** (`ssoSessionIdleTimeout: 300` no realm),
  **não** um timer em JavaScript — quem tem o token continua com ele até ele expirar, timer de cliente
  é sugestão, não controle. A condição para o Keycloak realmente ver a sessão como ociosa:
  `getToken()` (`auth.js`) só é chamado de dentro de `authenticatedFetch`, ou seja, só quando o usuário
  faz alguma coisa — **nunca transforme isso num `setInterval` renovando sozinho**, porque aí o
  Keycloak nunca veria ociosidade e a sessão ficaria logada para sempre. `accessTokenLifespan` é
  **600s (10 min)**, não 5: o read timeout da LLM no agent é 300s
  (`ChatClientConfig.LLM_READ_TIMEOUT`), e um token de 5 min expiraria no meio de uma resposta longa —
  o agent não tem refresh token no modelo SPA para renovar sozinho no meio da chamada. Com 10 min >
  300s, nada expira no meio. Preço aceito: um token já emitido continua válido até expirar, então há
  até ~10 min de janela residual depois que o usuário parou (trade-off inerente de bearer token). O
  timer de ociosidade do lado do cliente (`startIdleWatch`, `auth.js`) trata requisição em andamento
  como atividade — senão uma pergunta de até 310s (`REQUEST_TIMEOUT_MS` no `main.js`) deslogaria quem
  soltou o mouse enquanto a resposta ainda gerava.

- **Filtro de tools por role no agent (fase 5) é UX, não autorização** — reduz a superfície de prompt
  injection e evita o passeio de três telas (consultar → confirmar → 403 no clique) que `user2` fazia
  antes para descobrir que não podia excluir nada, mas **não substitui** a checagem real, que é
  `McpAuthorization` na API. Ver o javadoc de `ConfirmingToolCallbackProvider.allowed` — não remova a
  checagem da API achando que o filtro do agent basta; o filtro só esconde a tool de um modelo
  bem-comportado, e nada impede uma chamada direta ao `/mcp` sem passar pelo agent.

- **Eval usa usuário de máquina, sem perfil que desligue a segurança.** O `ToolSelectionEvalTest`
  injeta `ChatService` e chama direto — não passa pelo `SecurityFilterChain` do agent —, mas a chamada
  ao `/mcp` continua real desde a fase 4. `EvalAuthentication` (`logistic-agent/src/test`) obtém um
  token via *direct grant* no client `logistic-eval` (único com essa concessão habilitada, só para
  isto) para o usuário `eval-user`, e instala um `JwtAuthenticationToken` no
  `SecurityContextHolder` antes do teste — decodificado pelo **mesmo** `JwtDecoder` que valida
  requisições reais, não um atalho paralelo. `eval-user` tem a role `admin`, então nenhum caso do
  `tool-selection.json` perde tool por falta de permissão e o dataset não muda.
  `EvalEnvironmentCondition` ganhou um terceiro `reachable()` para o Keycloak, mesmo padrão dos dois
  que já existiam (API, LLM) — sem ele, a falha apareceria tarde, dentro da criação do `JwtDecoder`
  (que resolve o issuer no boot do contexto Spring), com uma mensagem que não aponta a causa. **Não
  existe, e não deve existir, um perfil `noauth`** que desligue a cadeia de segurança para o eval —
  regra inviolável do plano desta fase.

- **BFF como componente futuro, não decisão final.** A stack optou por SPA pública com PKCE em vez de
  Backend for Frontend: o agent é ponte LLM↔MCP, e empilhar nele sessão de browser, cookie e CSRF
  conflacionaria dois papéis. Consequência aceita **hoje**: o token de acesso fica no `sessionStorage`
  do browser, exposto a XSS — mitigado por lifespan curto (10 min) e `revokeRefreshToken` com
  `refreshTokenMaxReuse: 0` no realm (reuso do refresh token invalida a sessão). Num ecossistema com
  múltiplos agents e múltiplas APIs, o BFF é peça própria na frente de todos, não um papel extra dentro
  de um deles — é o desenho certo quando esse dia chegar, não um bug do desenho atual.

- **Delta de vocabulário.** A spec de autorização do MCP fala em *scopes* OAuth; aqui usamos *realm
  roles* do Keycloak (`read`/`write`) no lugar — mantém o vocabulário do domínio, que já aparecia em
  toda a stack antes da autenticação existir. Registrado aqui porque é a única divergência
  deliberada da spec (todo o resto — token exchange, audiência, "MUST NOT accept or transit any other
  tokens" — segue a normativa à risca).

### Tema de login (`infra/keycloak/themes/logistic`)

A tela de login é a primeira tela da aplicação, e usava a cara do Keycloak. O tema `logistic`
(`loginTheme` no realm) repete a paleta, a marca, os ícones e o botão claro/escuro do webui. É
volume `:ro` no `docker-compose.yaml`, e o `start-dev` **não cacheia tema**: editar `.ftl`/`.css`
e dar F5 basta — só realm importado exige recriar container, tema não.

- **`parent=base`, não `parent=keycloak`.** O tema `keycloak` carrega três folhas do PatternFly
  (v3 e v4) e prende o markup ao grid do Bootstrap — reestilizar por cima sai mais caro do que
  partir do `base`, que traz só os `.ftl` com a lógica dos fluxos e estilo nenhum. O preço: os
  `.ftl` herdados escrevem `${properties.kcXxxClass}` no HTML, e o que não estiver mapeado no
  `theme.properties` sai como classe vazia. **Elemento sem estilo quase sempre é propriedade
  faltando ali, não CSS faltando** — o `.ftl` do `base` é a lista do que precisa existir.
- **Só o `template.ftl` é sobrescrito.** `login.ftl`, `error.ftl`, `login-page-expired.ftl`,
  `logout-confirm.ftl` e o resto vêm do `base` e entram no layout pelo macro
  `registrationLayout` — por isso a tela de erro e a de logout já saem com a marca sem terem
  arquivo próprio. Os scripts que o template do `base` injeta (`authChecker`,
  `menu-button-links`, o handler de `data-once-link`) são funcionais e foram mantidos: tirá-los
  quebra o login em outra aba e o dropdown de idioma.
- **Os tokens de cor são cópia de `logistic-webui/src/style.css`**, não import: são origens
  diferentes (8090 x 5173) e o Keycloak só serve o que está dentro do tema. Mexeu na paleta do
  webui? Mexa aqui junto, senão o login descola do resto.
- **Botão claro/escuro:** mesmo mecanismo do webui — `data-theme` no `<html>`, chave `lp-theme`
  no `localStorage`, e um snippet inline no `<head>` que resolve o tema **antes da primeira
  pintura** (sem ele a tela pisca clara antes de virar escura). A preferência **não** é
  compartilhada com o chat: `localStorage` é por origem.
- **Ícone de olho da senha é máscara SVG** (`.pw-icon-show`/`.pw-icon-hide`). O `base` marca esse
  botão com classe de FontAwesome, que só existe no tema `keycloak`; o `passwordVisibility.js`
  do Keycloak troca uma classe pela outra no clique, então bastou dar significado às duas.
- **`.locale-list` precisa de `display: none` no CSS.** O `menu-button-links.js` abre o menu
  escrevendo `style.display = "block"` e fecha **removendo** a propriedade — o estado fechado é
  responsabilidade do tema. Hoje o dropdown nem aparece (um locale só), mas some a lista inteira
  se alguém adicionar o segundo e o CSS não estiver lá.
- **O português vem do realm, não do tema:** `internationalizationEnabled` +
  `supportedLocales: ["pt-BR"]` + `defaultLocale` no `logistic-realm.json`; as mensagens já
  existem no tema `base`. Com i18n desligada o Keycloak usa `messages_en` e a tela sai em inglês
  por mais que o tema esteja certo. E isso é mudança de **realm**: num container que já subiu,
  `--import-realm` não relê o JSON (`docker compose down -v`, ou editar pela UI/Admin API).
