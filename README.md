# Logistic Platform

Demo de uma LLM local respondendo perguntas sobre dados de logística em linguagem natural,
devolvendo texto, tabela ou gráfico. A LLM **não acessa o banco**: ela conversa com uma API
de logística através de um **MCP server** embutido nessa API — quem executa qualquer coisa
contra o Postgres é sempre a API, nunca o modelo.

## Arquitetura

```
Browser (logistic-webui :5173)
    │  POST /api/chat  { message, sessionId }
    ▼
logistic-agent (Spring Boot :8080)
    │  ChatClient (Spring AI)
    │    ├── LLM local OpenAI-compat  →  http://localhost:8200  (qwen3.6:35b)
    │    ├── tools locais: renderChart / renderTable
    │    └── tools MCP (descobertas do logistic-api)
    │            │  MCP Streamable HTTP
    │            ▼
logistic-api (Spring Boot :8081)
    │  @McpTool  →  Service  ←  @RestController
    │                  │
    │              Repository (Spring Data JPA)
    ▼
PostgreSQL 18 (pgvector) :5432   ← docker compose + Flyway
```

## Os 3 projetos

| Diretório | Stack | Porta | Responsabilidade |
|-----------|-------|-------|------------------|
| [`logistic-webui/`](logistic-webui/README.md) | Vite 8, Chart.js 4, marked (JS puro) | 5173 | Chat no browser; renderiza markdown, tabela e gráfico |
| [`logistic-agent/`](logistic-agent/README.md) | Java 21, Spring Boot 4, Spring AI (MCP client) | 8080 | Conversa com a LLM, descobre as tools MCP, monta o `renderData` |
| [`logistic-api/`](logistic-api/README.md) | Java 21, Spring Boot 4, JPA, Flyway, MCP server | 8081 | Dono do domínio e do banco; expõe REST + tools MCP |

## Pré-requisitos

- **Java 21** (ou superior)
- **Node 20+** com npm
- **Docker** com o plugin Compose v2, daemon rodando
- **LLM `qwen3.6:35b`** servida em `http://localhost:8200` com API compatível com OpenAI

A LLM é o único pré-requisito opcional na subida: o script avisa e sobe a stack mesmo assim,
mas o chat só responde quando o modelo estiver no ar.

## Rodando

```bash
./start.sh          # Linux / macOS
```

```bat
.\start.bat         REM Windows (wrapper do start.ps1)
```

Um comando sobe tudo; `Ctrl+C` derruba tudo. Ao final o script imprime:

| URL | O que é |
|-----|---------|
| <http://localhost:5173> | webui — a demo |
| <http://localhost:8080> | logistic-agent |
| <http://localhost:8081> | logistic-api |
| <http://localhost:8081/swagger-ui.html> | Swagger da API |

O que o script faz, em ordem: checa pré-requisitos e portas, compila o que mudou, sobe o
Postgres e espera o `pg_isready`, sobe a API e espera o `/actuator/health`, semeia o banco se
estiver vazio, sobe o agent e espera o `/api/chat/health`, sobe o webui. A espera entre
etapas não é opcional — sem ela o agent sobe antes das tools MCP existirem e falha o handshake.

## Opções dos scripts

| Bash | PowerShell | Efeito |
|------|-----------|--------|
| *(nenhuma)* | *(nenhuma)* | compila o que mudou, sobe tudo, semeia **se o banco estiver vazio** |
| `--build` | `-Build` | força recompilar api e agent, e `npm install` no webui |
| `--no-build` | `-NoBuild` | pula a checagem de build |
| `--reset` | `-Reset` | limpa o banco e reinsere o `dados.sql`, mesmo populado. Pede confirmação (`s/N`) |
| `--no-seed` | `-NoSeed` | nunca semeia, nem com banco vazio |
| `--yes` | `-Yes` | pula a confirmação do `--reset` |
| `--help` | `-Help` | imprime a tabela de flags |

Comportamentos que não são óbvios:

- **O seed roda sozinho só na primeira vez.** O script conta os motoristas; `0` significa banco
  vazio e ele aplica o `dados.sql`. Da segunda execução em diante, imprime
  `Banco já populado (N motoristas) — seed ignorado.` O `TRUNCATE` no topo do `dados.sql` é
  inofensivo nesse caminho: só executa quando não há o que apagar.
- **`--reset` gera um dataset diferente a cada execução.** O seed usa `random()` na distribuição
  de rotas e pedidos, então os gráficos mudam. É reset de **dados**, não de estrutura: o schema
  e o histórico do Flyway ficam intactos.
- **`Ctrl+C` não perde dados.** O shutdown manda `TERM` nas 3 apps (`KILL` se não morrerem em 10s)
  e roda `docker compose stop` — para o container, preserva o volume.

## Subida manual

Para debugar no IDE, sem os scripts:

```bash
# 1. banco
docker compose -f logistic-api/docker-compose.yaml up -d

# 2. api (Flyway cria o schema na subida)
cd logistic-api && ./mvnw spring-boot:run

# 3. seed, se o banco estiver vazio
docker exec -i logisticdb psql -U postgres -d logisticdb \
  < logistic-api/src/main/resources/db/seed/dados.sql

# 4. agent (precisa da api já no ar)
cd logistic-agent && ./mvnw spring-boot:run

# 5. webui
cd logistic-webui && npm install && npm run dev
```

## Logs

Cada app escreve num arquivo próprio; o terminal do script mostra só o progresso e as URLs.

```bash
tail -f logs/logistic-agent.log
tail -f logs/logistic-api.log
tail -f logs/logistic-webui.log
```

## Perguntas de exemplo

Roteiro de demo e teste de fumaça, com o navegador em <http://localhost:5173>:

| Pergunta | Esperado |
|----------|----------|
| quantos motoristas existem? | texto com o número |
| liste os pedidos entregues em SP | tabela renderizada |
| gráfico de pedidos por status | gráfico bar ou pie, status em PT-BR |
| e em MG? | mantém o contexto da pergunta anterior |
| cadastre um veículo chamado Truck X com capacidade 180 | criado; aparece em `GET :8081/api/vehicles` |
| qual a taxa de falha de entrega por estado? | agrega via tool MCP e responde |

## Troubleshooting

| Sintoma | Causa provável | Saída |
|---------|----------------|-------|
| `porta 5432 já está ocupada` | container `logisticdb` de outra sessão, ou Postgres nativo | `docker rm -f logisticdb`, ou pare o serviço local |
| `porta 8080/8081/5173 já está ocupada` | app da execução anterior ficou de pé | `jps` / `lsof -i :8080` e mate o processo |
| `AVISO: LLM não respondeu` | modelo fora do ar | suba o `qwen3.6:35b` em `http://localhost:8200`; a stack não precisa reiniciar |
| chat responde "erro ao processar" | agent subiu sem as tools MCP | confira `logs/logistic-agent.log`; a API tem que estar respondendo `/actuator/health` **antes** do agent |
| `logistic-api não subiu em 90s` | Flyway falhou ou banco inacessível | `tail -n 50 logs/logistic-api.log` |
| gráfico não aparece | o modelo respondeu só texto | reformule pedindo "gráfico de ..." explicitamente |

## Aviso de segurança

> **Esta stack não deve ser exposta na rede.** Ela é um ambiente de desenvolvimento local:
>
> - a `logistic-api` **não tem autenticação** — qualquer um que alcance a porta 8081 lê e escreve no domínio;
> - o Postgres sobe com **credenciais padrão** (`postgres` / `postgres`) e a porta 5432 publicada;
> - o **MCP server é aberto**, sem token, e inclui a tool `execute_query`, que roda `SELECT` arbitrário
>   (numa role read-only, mas ainda assim lê o banco inteiro);
> - a role `logistic_ro` sobe com senha fixa no `V2__readonly_role.sql`, versionada no repositório.
>
> Rode em `localhost`. Não publique em rede compartilhada nem na internet.
