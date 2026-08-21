# logistic-webui

Frontend da plataforma — Vite 8 + Chart.js 4 + marked, JavaScript puro, sem framework.
Visão geral e como subir tudo junto: [README da raiz](../README.md).

## Rodar isolado

```bash
npm install
npm run dev
```

Sobe em <http://localhost:5173>. Para responder de verdade, precisa do `logistic-agent`
rodando na 8080.

## Configuração

| Variável | Padrão | O que é |
|----------|--------|---------|
| `VITE_API_URL` | `http://localhost:8080/api/chat` | Endpoint de chat do `logistic-agent` |

O `.env` é o da raiz do repo (`../.env`, copiado de `../.env.example`) — o Vite carrega de
lá via `envDir` em `vite.config.js`, não daqui. Ajuste `VITE_API_URL` lá se o agent não
estiver na 8080. Só variáveis prefixadas com `VITE_` chegam ao bundle do browser; o resto
do `.env` (chaves do Langfuse etc.) fica de fora.

## Contrato com o agent

Request (`POST` para `VITE_API_URL`):

```json
{ "message": "quantos motoristas existem?", "sessionId": "session-1739..." }
```

O `sessionId` é gerado no browser e persistido no `localStorage` (`chat-session-id`).
"Nova conversa" gera um id novo — o agent perde o contexto anterior.

Response:

```json
{
  "role": "assistant",
  "content": "texto em markdown",
  "renderData": null
}
```

`content` é renderizado como markdown. `renderData` é `null`, ou um destes:

```json
{ "type": "chart", "title": "...", "chartType": "bar|line|pie|doughnut",
  "labels": ["SP","RJ"], "datasets": [{ "label": "Entregas", "data": [42, 30] }] }

{ "type": "table", "title": "...", "columns": ["Estado","Entregas"],
  "rows": [["SP", "42"], ["RJ", "30"]] }
```

As células de `rows` são sempre string, inclusive números.

Se o agent mandar algo fora desse contrato, o ajuste é no `logistic-agent` — este projeto
não adapta o formato.
