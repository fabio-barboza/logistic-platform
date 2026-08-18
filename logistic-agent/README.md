# logistic-agent

Conversa com a LLM local, descobre as tools MCP do `logistic-api` e devolve para o webui
texto em markdown, opcionalmente acompanhado de um gráfico ou tabela.

**Não tem datasource.** Nenhuma dependência de banco no `pom.xml` — todo acesso a dados passa
pelas tools MCP da `logistic-api`.

Visão geral e como subir tudo junto: [README da raiz](../README.md).

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
(`ChatClientConfig`), 10s para conectar e 120s para ler. Um modelo local com contexto grande
passa fácil dos 30s default.

## Memória de conversa

`MessageWindowChatMemory` em memória, janela de 20 mensagens, particionada pelo `sessionId` que
vem no request. Reiniciar o agent zera todas as conversas — é demo, não tem persistência.

## System prompt

Constante `SYSTEM_PROMPT` em `ChatClientConfig`. Ele carrega as decisões que o modelo não teria
como adivinhar:

- **Idioma e tom** — português do Brasil, conciso.
- **Ordem de preferência entre tools** — primeiro as tipadas (`searchDrivers`, `countOrdersBy`, …),
  `executeQuery` só para join, agregação ou recorte fora do catálogo.
- **Contagem** — nunca listar registros e contar manualmente; usar `countOrdersBy`/`countRoutesBy`
  ou `SELECT COUNT(*)` via `executeQuery`. Contar à mão erra em listas grandes.
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
2. `RenderTool` valida (`chartType` tem que ser `bar`, `line`, `pie` ou `doughnut`) e monta um
   `ChartContent` ou `TableContent`.
3. O objeto é guardado no `RenderHolder`, um bean **request-scoped** — cada requisição HTTP tem
   o seu, então duas conversas simultâneas não misturam render.
4. Terminada a chamada do `ChatClient`, `ChatService` lê o holder e devolve
   `new ChatMessageDTO("assistant", content, renderData)`.
5. Se o modelo não chamou nenhuma das duas, o holder está vazio e `renderData` sai `null` —
   o webui renderiza só o markdown.

### Por que `rows` é `List<List<String>>`

O tipo Java do parâmetro da tool é o que vira JSON Schema. `List<List<Object>>` gera
`"items": { }` — schema vazio, sem restrição nenhuma; sem isso o modelo improvisa a estrutura da
célula (já mandou `{"text": "SP"}` no lugar de `"SP"`). Com `List<List<String>>` o schema exige
string e o modelo acerta. O webui renderiza com `textContent`, então o valor exibido é idêntico.

## Contrato com o webui

Request — `POST /api/chat`:

```json
{ "message": "quantos motoristas existem?", "sessionId": "session-1739..." }
```

Response:

```json
{ "role": "assistant", "content": "texto em markdown", "renderData": null }
```

`renderData` é `null`, ou um destes:

```json
{ "type": "chart", "title": "...", "chartType": "bar|line|pie|doughnut",
  "labels": ["SP","RJ"], "datasets": [{ "label": "Entregas", "data": [42, 30] }] }

{ "type": "table", "title": "...", "columns": ["Estado","Entregas"],
  "rows": [["SP", "42"], ["RJ", "30"]] }
```
