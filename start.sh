#!/usr/bin/env bash
#
# Sobe a stack inteira da Logistic Platform: Postgres, logistic-api, logistic-agent e logistic-webui.
# Ctrl+C derruba tudo.
#
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
COMPOSE_FILE="$ROOT_DIR/logistic-api/docker-compose.yaml"
SEED_FILE="$ROOT_DIR/logistic-api/src/main/resources/db/seed/dados.sql"
DB_CONTAINER="logisticdb"

API_PORT=8081
AGENT_PORT=8080
WEBUI_PORT=5173
DB_PORT=5432
LLM_URL="http://localhost:8200"

# Preenchidos durante a subida; usados pelo shutdown.
API_PID=""
AGENT_PID=""
WEBUI_PID=""
STACK_STARTED=false

# Flags
FORCE_BUILD=false
SKIP_BUILD=false
RESET_DB=false
NO_SEED=false
ASSUME_YES=false

# --------------------------------------------------------------------------------------
# Saída
# --------------------------------------------------------------------------------------

info() {
    echo "  $1"
}

step() {
    echo ""
    echo "==> $1"
}

# Só derruba a stack se este script chegou a subir alguma coisa. Falha nas pré-checagens
# (porta ocupada, por exemplo) não pode parar um container que não é nosso.
fail() {
    echo ""
    echo "ERRO: $1" >&2
    if [ "$STACK_STARTED" = true ]; then
        shutdown 1
    fi
    exit 1
}

warn() {
    echo "  AVISO: $1"
}

# --------------------------------------------------------------------------------------
# Flags
# --------------------------------------------------------------------------------------

print_help() {
    cat <<'EOF'
Uso: ./start.sh [opções]

  (nenhuma)     sobe tudo sem compilar, semeia se o banco estiver vazio
  --build       recompila api e agent, e roda npm install no webui
  --no-build    nunca compila: falha se faltar jar ou node_modules (o padrão compila
                nesse caso, por ser a primeira execução)
  --reset       limpa o banco e reinsere o dados.sql, mesmo populado (pede confirmação)
  --no-seed     nunca semeia, nem com banco vazio
  --yes         pula a confirmação do --reset
  --help        imprime esta tabela

URLs depois da subida:
  http://localhost:5173   webui
  http://localhost:8080   logistic-agent
  http://localhost:8081   logistic-api
  http://localhost:8081/swagger-ui.html   Swagger
EOF
}

parse_flags() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --build)    FORCE_BUILD=true ;;
            --no-build) SKIP_BUILD=true ;;
            --reset)    RESET_DB=true ;;
            --no-seed)  NO_SEED=true ;;
            --yes|-y)   ASSUME_YES=true ;;
            --help|-h)  print_help; exit 0 ;;
            *)          echo "Flag desconhecida: $1" >&2; echo ""; print_help; exit 1 ;;
        esac
        shift
    done

    if [ "$FORCE_BUILD" = true ] && [ "$SKIP_BUILD" = true ]; then
        fail "--build e --no-build são mutuamente exclusivos."
    fi
    if [ "$RESET_DB" = true ] && [ "$NO_SEED" = true ]; then
        fail "--reset e --no-seed são mutuamente exclusivos."
    fi
}

# --------------------------------------------------------------------------------------
# Pré-checagens
# --------------------------------------------------------------------------------------

port_is_busy() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn "sport = :$port" 2>/dev/null | grep -q LISTEN
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    else
        # Sem ferramenta para checar: não bloqueia a subida.
        return 1
    fi
}

check_java_version() {
    command -v java >/dev/null 2>&1 || fail "java não encontrado. Instale o JDK 21."

    local version_line major
    version_line="$(java -version 2>&1 | head -n 1)"
    major="$(echo "$version_line" | sed -E 's/.*"([0-9]+).*/\1/')"

    if ! [[ "$major" =~ ^[0-9]+$ ]]; then
        warn "não consegui identificar a versão do Java em: $version_line"
        return
    fi
    if [ "$major" -lt 21 ]; then
        fail "Java $major encontrado; a stack precisa de Java 21 ou superior."
    fi
    info "Java $major"
}

check_docker() {
    command -v docker >/dev/null 2>&1 || fail "docker não encontrado. Instale o Docker."
    docker info >/dev/null 2>&1 || fail "o daemon do Docker não está rodando. Suba o Docker e tente de novo."
    docker compose version >/dev/null 2>&1 || fail "'docker compose' não disponível. Instale o plugin Compose v2."
    info "Docker ok"
}

check_node() {
    command -v node >/dev/null 2>&1 || fail "node não encontrado. Instale o Node 20 ou superior."
    command -v npm >/dev/null 2>&1 || fail "npm não encontrado. Instale o Node 20 ou superior."
    info "Node $(node -v)"
}

db_container_is_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null)" = "true" ]
}

check_ports() {
    local port label busy=false

    # A 5432 tem tratamento próprio: se quem está ouvindo é o nosso container, o compose
    # apenas o reaproveita — não é conflito.
    if port_is_busy "$DB_PORT"; then
        if db_container_is_running; then
            info "container $DB_CONTAINER já está de pé — será reaproveitado"
        else
            echo "  porta $DB_PORT (Postgres) ocupada por outro processo" >&2
            busy=true
        fi
    fi

    for entry in "$API_PORT:logistic-api" "$AGENT_PORT:logistic-agent" "$WEBUI_PORT:logistic-webui"; do
        port="${entry%%:*}"
        label="${entry##*:}"
        if port_is_busy "$port"; then
            echo "  porta $port ($label) já está ocupada" >&2
            busy=true
        fi
    done

    if [ "$busy" = true ]; then
        fail "libere as portas acima antes de subir. Um container de outra sessão pode estar segurando a 5432: 'docker rm -f $DB_CONTAINER'."
    fi
    info "portas 8080, 8081 e 5173 livres"
}

check_llm() {
    if curl -s -o /dev/null --max-time 3 "$LLM_URL/v1/models"; then
        info "LLM respondendo em $LLM_URL"
    else
        warn "LLM não respondeu em $LLM_URL — a stack sobe, mas o chat vai falhar até o modelo estar no ar."
    fi
}

check_prereqs() {
    step "Checando pré-requisitos"
    command -v curl >/dev/null 2>&1 || fail "curl não encontrado — necessário para os healthchecks."
    check_docker
    check_java_version
    check_node
    check_ports
    check_llm
}

# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

jar_path() {
    local project="$1"
    find "$ROOT_DIR/$project/target" -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | head -n 1
}

build_maven_project() {
    local project="$1"
    step "Compilando $project"
    (cd "$ROOT_DIR/$project" && ./mvnw -q -DskipTests clean package) \
        || fail "falha ao compilar $project. Rode './mvnw clean package' em $project para ver o erro completo."
    info "$project compilado"
}

build_webui() {
    step "Instalando dependências do logistic-webui"
    (cd "$ROOT_DIR/logistic-webui" && npm install --silent) \
        || fail "falha no 'npm install' do logistic-webui."
    info "dependências instaladas"
}

# Compilar é a exceção, não a regra: o padrão é subir o que já está construído. O único
# caso em que o script compila sozinho é quando não há artefato nenhum — sem jar ou sem
# node_modules não tem o que subir.
build_all() {
    if [ "$FORCE_BUILD" = true ]; then
        step "Recompilando tudo (--build)"
        build_maven_project logistic-api
        build_maven_project logistic-agent
        build_webui
        return
    fi

    step "Verificando artefatos"

    for project in logistic-api logistic-agent; do
        if [ -n "$(jar_path "$project")" ]; then
            info "$project — jar encontrado"
        elif [ "$SKIP_BUILD" = true ]; then
            fail "--no-build informado, mas $project/target não tem jar. Rode com --build."
        else
            info "$project sem jar — compilando (primeira execução)"
            build_maven_project "$project"
        fi
    done

    if [ -d "$ROOT_DIR/logistic-webui/node_modules" ]; then
        info "logistic-webui — node_modules encontrado"
    elif [ "$SKIP_BUILD" = true ]; then
        fail "--no-build informado, mas logistic-webui/node_modules não existe. Rode com --build."
    else
        info "logistic-webui sem node_modules — instalando (primeira execução)"
        build_webui
    fi

    info "mudou o código? rode com --build"
}

# --------------------------------------------------------------------------------------
# Espera
# --------------------------------------------------------------------------------------

# O 4º argumento é o PID da app. Se ela morreu (jar corrompido, porta em uso, exception
# no startup), não faz sentido esperar o timeout inteiro — aborta na hora.
wait_for_http() {
    local url="$1" label="$2" timeout="$3" pid="${4:-}"
    local waited=0

    echo -n "  aguardando $label"
    while [ "$waited" -lt "$timeout" ]; do
        if curl -s -o /dev/null -f --max-time 3 "$url"; then
            echo " ok (${waited}s)"
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            echo " processo morreu"
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " timeout"
    return 1
}

wait_for_port() {
    local port="$1" label="$2" timeout="$3" pid="${4:-}"
    local waited=0

    echo -n "  aguardando $label"
    while [ "$waited" -lt "$timeout" ]; do
        if port_is_busy "$port"; then
            echo " ok (${waited}s)"
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            echo " processo morreu"
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " timeout"
    return 1
}

wait_for_postgres() {
    local timeout=60
    local waited=0

    echo -n "  aguardando Postgres"
    while [ "$waited" -lt "$timeout" ]; do
        if docker exec "$DB_CONTAINER" pg_isready -U postgres -d logisticdb >/dev/null 2>&1; then
            echo " ok (${waited}s)"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " falhou"
    return 1
}

# --------------------------------------------------------------------------------------
# Subida
# --------------------------------------------------------------------------------------

start_postgres() {
    step "Subindo Postgres"
    STACK_STARTED=true
    docker compose -f "$COMPOSE_FILE" up -d || fail "falha ao subir o Postgres via docker compose."
    wait_for_postgres || fail "Postgres não ficou pronto em 60s. Veja: docker logs $DB_CONTAINER"
}

# O 'exec' faz o java substituir o subshell, então $! é o PID do próprio java —
# sem isso o TERM iria para o subshell e deixaria o java órfão.
start_java_app() {
    local project="$1" logfile="$2"
    local jar
    jar="$(jar_path "$project")"

    step "Subindo $project"
    ( cd "$ROOT_DIR/$project" && exec java -jar "$jar" ) > "$logfile" 2>&1 &
}

start_api() {
    start_java_app logistic-api "$LOG_DIR/logistic-api.log"
    API_PID=$!
    if ! wait_for_http "http://localhost:$API_PORT/actuator/health" "logistic-api" 90 "$API_PID"; then
        fail "logistic-api não subiu. Veja: tail -n 50 $LOG_DIR/logistic-api.log"
    fi
}

start_agent() {
    start_java_app logistic-agent "$LOG_DIR/logistic-agent.log"
    AGENT_PID=$!
    if ! wait_for_http "http://localhost:$AGENT_PORT/api/chat/health" "logistic-agent" 90 "$AGENT_PID"; then
        fail "logistic-agent não subiu. Veja: tail -n 50 $LOG_DIR/logistic-agent.log"
    fi
}

start_webui() {
    step "Subindo logistic-webui"
    ( cd "$ROOT_DIR/logistic-webui" && exec npm run dev ) > "$LOG_DIR/logistic-webui.log" 2>&1 &
    WEBUI_PID=$!
    if ! wait_for_port "$WEBUI_PORT" "logistic-webui" 60 "$WEBUI_PID"; then
        fail "logistic-webui não subiu. Veja: tail -n 50 $LOG_DIR/logistic-webui.log"
    fi
}

# --------------------------------------------------------------------------------------
# Seed
# --------------------------------------------------------------------------------------

# Conta pela role postgres, não pela logistic_ro: essa última só tem SELECT e existe
# exclusivamente para a tool execute_query do logistic-api.
count_drivers() {
    docker exec "$DB_CONTAINER" psql -U postgres -d logisticdb -tAc "SELECT count(*) FROM driver" 2>/dev/null | tr -d '[:space:]'
}

run_seed() {
    docker exec -i "$DB_CONTAINER" psql -U postgres -d logisticdb -q < "$SEED_FILE" \
        || fail "falha ao aplicar o seed ($SEED_FILE)."
    info "Seed aplicado ($(count_drivers) motoristas)."
}

confirm_reset() {
    if [ "$ASSUME_YES" = true ]; then
        return 0
    fi
    local answer
    read -r -p "  --reset vai apagar TODOS os dados das tabelas e repopular. Continuar? (s/N) " answer
    case "$answer" in
        s|S|sim|SIM) return 0 ;;
        *) return 1 ;;
    esac
}

seed_if_empty() {
    step "Verificando dados de demonstração"

    local drivers
    drivers="$(count_drivers)"
    if ! [[ "$drivers" =~ ^[0-9]+$ ]]; then
        fail "não consegui contar os motoristas no banco. O Flyway rodou? Veja $LOG_DIR/logistic-api.log"
    fi

    if [ "$RESET_DB" = true ]; then
        if confirm_reset; then
            info "Reset pedido — repopulando com dados de demonstração..."
            run_seed
        else
            info "Reset cancelado — dados preservados."
        fi
        return
    fi

    if [ "$NO_SEED" = true ]; then
        info "Seed desativado (--no-seed) — banco com $drivers motoristas."
        return
    fi

    if [ "$drivers" -eq 0 ]; then
        info "Banco vazio — populando com dados de demonstração..."
        run_seed
    else
        info "Banco já populado ($drivers motoristas) — seed ignorado."
    fi
}

# --------------------------------------------------------------------------------------
# Shutdown
# --------------------------------------------------------------------------------------

# O 'npm run dev' cria o Vite como processo filho; matar só o npm deixaria o Vite
# segurando a 5173. Por isso o TERM vai para o pai e para os filhos diretos dele.
kill_with_children() {
    local pid="$1" signal="$2"
    local child
    for child in $(pgrep -P "$pid" 2>/dev/null); do
        kill_with_children "$child" "$signal"
    done
    kill "-$signal" "$pid" 2>/dev/null
}

stop_pid() {
    local pid="$1" label="$2"
    [ -n "$pid" ] || return 0
    kill -0 "$pid" 2>/dev/null || return 0

    info "parando $label (pid $pid)"
    kill_with_children "$pid" TERM

    local waited=0
    while [ "$waited" -lt 10 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        warn "$label não respondeu ao TERM — enviando KILL"
        kill_with_children "$pid" KILL
    fi
}

SHUTDOWN_DONE=false

shutdown() {
    local exit_code="${1:-0}"

    # Idempotente: Ctrl+C durante a subida pode disparar isso mais de uma vez.
    if [ "$SHUTDOWN_DONE" = true ]; then
        return
    fi
    SHUTDOWN_DONE=true

    trap '' INT TERM
    step "Derrubando a stack"

    stop_pid "$WEBUI_PID" logistic-webui
    stop_pid "$AGENT_PID" logistic-agent
    stop_pid "$API_PID" logistic-api

    # 'stop' e não 'down': preserva o volume e os dados para o próximo start.
    info "parando o Postgres (dados preservados)"
    docker compose -f "$COMPOSE_FILE" stop >/dev/null 2>&1

    echo ""
    echo "Stack derrubada. Dados do Postgres preservados."
    exit "$exit_code"
}

print_urls() {
    cat <<EOF

================================================================
  Logistic Platform no ar

  webui      http://localhost:$WEBUI_PORT
  agent      http://localhost:$AGENT_PORT
  api        http://localhost:$API_PORT
  Swagger    http://localhost:$API_PORT/swagger-ui.html

  Logs em logs/ (ex.: tail -f logs/logistic-agent.log)
  Ctrl+C derruba tudo.
================================================================
EOF
}

# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

main() {
    parse_flags "$@"
    trap shutdown INT TERM

    mkdir -p "$LOG_DIR"

    check_prereqs
    build_all
    start_postgres
    start_api
    seed_if_empty
    start_agent
    start_webui
    print_urls

    # Fica em foreground até o Ctrl+C; se qualquer app morrer sozinho, derruba o resto.
    while true; do
        for entry in "$API_PID:logistic-api" "$AGENT_PID:logistic-agent" "$WEBUI_PID:logistic-webui"; do
            local pid="${entry%%:*}" label="${entry##*:}"
            if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
                warn "$label morreu — derrubando o resto da stack. Veja logs/$label.log"
                shutdown 1
            fi
        done
        sleep 3
    done
}

main "$@"
