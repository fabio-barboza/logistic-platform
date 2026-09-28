# logistic-agent

Conversa com a LLM local, descobre as tools MCP do `logistic-api` e devolve para o webui
texto em markdown, opcionalmente acompanhado de um gráfico ou tabela.

**Não toca o banco de domínio.** Todo dado de motorista, veículo, pedido e rota passa pelas tools
MCP da `logistic-api`. O datasource do agent aponta para o `agentdb` (Postgres próprio, 5433) e
guarda só estado de conversa: histórico de chat, ação pendente de confirmação e intenção de escrita.

Visão geral e como subir tudo junto: [README da raiz](../README.md).

## Convenções de pacote

Clean Architecture pragmática — regras completas no [CLAUDE.md da raiz](../CLAUDE.md#convenções-de-pacote-clean-architecture).
Resumo:

| Raiz | Contém |
|------|--------|
| `core/` | `domain/` (só modelo), `usecase/<domínio>/` (um `@Service` por caso de uso), `gateway/` (interfaces para o externo), `service/` (etapa compartilhada), `settings/`, `support/`, e pastas de conceito (`agent/`, `agent/tools/`, `guardrail/`) |
| `infra/` | `gateway/` (`*GatewayImpl`), `repository/` (Spring Data), `entity/` (`*Entity`), `dto/`, `mapper/`, `support/`, `client/` |
| `entrypoint/` | `controller/`, `exception/`, `request/`, `response/`, `mapper/`, e outros entrypoints lado a lado |
| `config/` | todo `@Configuration` e propriedades tipadas |
| `security/` | módulo transversal (autenticação e token exchange) — **exceção documentada**; o `core` não o importa |

- `core` não importa `infra`, `entrypoint`, `config` nem os módulos de feature; SDK de LLM, JPA e
  cliente HTTP ficam no `infra`.
- controller → use case → gateway. Use case não chama use case; tool do laço do agente fica no core e
  chama gateway.
- Sufixos, nunca prefixo `I*`. Interface só para gateway, repository Spring Data e tipo de modelo com
  motivo (sealed, callback).
- Testes espelham o pacote do que testam.

Árvore atual (`br.com.fabio.logisticagent`), no mesmo formato do `resume-ai`:

```
config/                 @Configuration: ChatClient, segurança, CORS, Langfuse, MCP, log de tool calls, purga
core/
  agent/                holders request-scoped do laço (render, tool calls, resultado de query,
                        pendência) e ActionLabels (textos PT das tools de escrita)
  agent/tools/          RenderTool
  domain/chat/          ChatMessage, PendingAction
  domain/render/        RenderableContent, ChartContent, TableContent, Dataset
  domain/exception/     PermissionDeniedException
  gateway/              ChatModel, ChatHistory, McpTool, PendingAction, ConversationState,
                        Tracing, BackendHealth (sufixo Gateway)
  guardrail/            WriteConfirmationGuardrail, RequiredArgumentsCheck, DeletionTargetLookup,
                        UnbackedClaimGuardrail, AnswerNotices
  settings/             StatePurgeSettings
  usecase/chat/         SendMessageUseCase, ConfirmActionUseCase
  usecase/health/       CheckHealthUseCase
  usecase/maintenance/  PurgeAgentStateUseCase
infra/
  client/               ConfirmingToolCallbackProvider
  entity/               ConversationStateEntity, PendingActionEntity
  gateway/              *GatewayImpl, uma por interface do core
  mapper/               PendingActionEntityMapper
  repository/           ConversationStateRepository, PendingActionRepository
  support/              DetailsJsonConverter
entrypoint/
  controller/           ChatController
  mapper/               ResponseMapper
  request/              ChatRequest, ConfirmRequest
  response/             ChatResponse, PendingActionResponse, HealthResponse
  scheduler/            AgentStatePurgeScheduler
security/               AuthenticatedUser, TokenExchangeService
```

Exceções à regra (anotações de framework no core, `RenderableContent` reaproveitado na resposta,
decorator do MCP em `infra/client`, holders request-scoped entre `infra` e `core`) estão justificadas no [CLAUDE.md](../CLAUDE.md#convenções-de-pacote-clean-architecture).

## Rodar isolado

```bash
./mvnw spring-boot:run
```

Sobe na 8080. Precisa da `logistic-api` já respondendo na 8081: as tools MCP são descobertas
no startup, e sem elas o handshake falha. O `McpServerUnavailableFailureAnalyzer` transforma
esse erro numa mensagem legível em vez de um stack trace.

Healthcheck: `GET http://localhost:8080/api/chat/health`.

## Configuração do LLM

`application.yml`:

| Chave | Valor |
|-------|-------|
| `spring.ai.openai.base-url` | `http://localhost:8200` |
| `spring.ai.openai.api-key` | `not-needed` (o servidor local não valida) |
| `spring.ai.openai.chat.options.model` | `qwen3.6:35B` |
| `spring.ai.openai.chat.options.temperature` | `0.7` |
| `spring.ai.openai.chat.options.max-tokens` | `16000` |

Qualquer servidor com API compatível com OpenAI serve — troque `base-url` e `model`.

Os timeouts do cliente HTTP não vêm do YAML: estão no bean `llmTimeoutCustomizer`
(`ChatClientConfig`), 10s para conectar e 300s para ler. A chamada não é streaming: o read timeout cobre a geração
inteira. O webui aborta em 310s — mudou um, mude o outro.

## Memória de conversa

`MessageWindowChatMemory` sobre `JdbcChatMemoryRepository` (tabela `spring_ai_chat_memory` no
`agentdb`), janela de 20 mensagens, particionada por `sub|sessionId` (`AuthenticatedUser.conversationId`).
Sobrevive a restart; o `PurgeAgentStateUseCase` apaga conversa parada há 24h.

## System prompt

Arquivo `src/main/resources/prompts/system_prompt.md`, carregado pelo `ChatModelGatewayImpl`. Ele carrega as decisões que o modelo não teria
como adivinhar:

- **Idioma e tom** — português do Brasil, conciso.
- **Leitura só por `executeQuery`** — não existem tools de busca ou contagem; as outras tools
  apenas escrevem. E nunca apresentar dados que não vieram do retorno da tool nesta mesma resposta,
  mesmo que a conversa anterior pareça conter a informação.
- **Contagem e ranking** — nunca listar registros e contar manualmente: `SELECT COUNT(*)` para
  contar, `ORDER BY ... LIMIT n` para ranquear. Contar à mão erra em listas grandes.
- **Tradução dos status para PT-BR** — a tabela `COMPLETED = Concluído` e afins.
- **Quando renderizar** — `renderChart` para pedido de gráfico, `renderTable` para tabela ou
  quando dados tabulares forem mais claros que texto corrido.
- **Nunca inventar dados** — tool vazia significa "não há registros".

Mudou o catálogo de tools da API, revise este prompt.

## Descoberta das tools MCP

Cliente MCP configurado em `application.yml`, transporte **Streamable HTTP**:

```yaml
spring.ai.mcp.client:
  type: SYNC
  request-timeout: 60s
  toolcallback.enabled: true
  streamable-http.connections.logistic:
    url: http://localhost:8081
    endpoint: /mcp
```

Com `toolcallback.enabled: true`, o Spring AI injeta um `ToolCallbackProvider` com tudo que o
servidor MCP anunciou. `ChatClientConfig` registra esse provider no `ChatClient` via
`defaultToolCallbacks(...)` — o agent não conhece os nomes das tools em tempo de compilação,
elas chegam do servidor no startup.

## Como `renderChart` / `renderTable` viram `renderData`

As duas tools vivem aqui, não na API: são contrato de UI, não de domínio.

1. O modelo chama `renderChart` ou `renderTable` como qualquer outra tool.
2. O modelo não manda os dados: informa quais colunas do resultado da última `executeQuery` usar.
   `RenderTool` lê as linhas do `QueryResultHolder`, valida (`chartType` tem que ser `bar`, `line`,
   `pie` ou `doughnut`, colunas têm que existir) e monta um `ChartContent` ou `TableContent`.
3. O objeto é guardado no `RenderHolder`, um bean **request-scoped** — cada requisição HTTP tem
   o seu, então duas conversas simultâneas não misturam render.
4. Terminada a chamada à LLM (`ChatModelGateway`), o `SendMessageUseCase` lê o holder e devolve
   um `ChatMessage("assistant", content, renderData, pendingAction)`, que o `ChatController` converte
   em `ChatResponse` pelo `ResponseMapper`.
5. Se o modelo não chamou nenhuma das duas, o holder está vazio e `renderData` sai `null` —
   o webui renderiza só o markdown.

## Contrato com o webui

Request — `POST /api/chat`:

```json
{ "message": "quantos motoristas existem?", "sessionId": "session-1739..." }
```

Response:

```json
{ "role": "assistant", "content": "texto em markdown", "renderData": null, "pendingAction": null }
```

`pendingAction` vem preenchido quando o modelo pediu uma escrita — o webui desenha o card e
confirma por `POST /api/chat/confirm` com `{ "sessionId", "actionId", "approved" }`:

```json
{ "id": "…", "tool": "createDriver", "summary": "Cadastrar um novo motorista",
  "arguments": { "Nome": "João" }, "destructive": false }
```

`renderData` é `null`, ou um destes:

```json
{ "type": "chart", "title": "...", "chartType": "bar|line|pie|doughnut",
  "labels": ["SP","RJ"], "datasets": [{ "label": "Entregas", "data": [42, 30] }] }

{ "type": "table", "title": "...", "columns": ["Estado","Entregas"],
  "rows": [["SP", "42"], ["RJ", "30"]] }
```
