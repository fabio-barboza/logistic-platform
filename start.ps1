<#
.SYNOPSIS
    Sobe a stack inteira da Logistic Platform: Postgres, logistic-api, logistic-agent e logistic-webui.
.DESCRIPTION
    Ctrl+C derruba tudo. Equivalente Windows do start.sh.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$NoBuild,
    [switch]$Reset,
    [switch]$NoSeed,
    [switch]$Yes,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$RootDir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir       = Join-Path $RootDir 'logs'
$ComposeFile  = Join-Path $RootDir 'logistic-api\docker-compose.yaml'
$SeedFile     = Join-Path $RootDir 'logistic-api\src\main\resources\db\seed\dados.sql'
$DbContainer  = 'logisticdb'

$ApiPort   = 8081
$AgentPort = 8080
$WebuiPort = 5173
$DbPort    = 5432
$LlmUrl    = 'http://localhost:8200'

# Preenchidos durante a subida; usados pelo Stop-Stack.
$script:ApiProcess     = $null
$script:AgentProcess   = $null
$script:WebuiProcess   = $null
$script:StackStarted   = $false
$script:ShutdownDone   = $false

# --------------------------------------------------------------------------------------
# Saída
# --------------------------------------------------------------------------------------

function Write-Info {
    param([string]$Message)
    Write-Host "  $Message"
}

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "==> $Message"
}

function Write-Warn {
    param([string]$Message)
    Write-Host "  AVISO: $Message" -ForegroundColor Yellow
}

class StackFailure : System.Exception {
    StackFailure([string]$message) : base($message) { }
}

function Fail {
    param([string]$Message)
    throw [StackFailure]::new($Message)
}

# --------------------------------------------------------------------------------------
# Flags
# --------------------------------------------------------------------------------------

function Show-Help {
    @'
Uso: .\start.ps1 [opções]

  (nenhuma)     compila o que mudou, sobe tudo, semeia se o banco estiver vazio
  -Build        força recompilar api e agent, e npm install no webui
  -NoBuild      pula a checagem de build
  -Reset        limpa o banco e reinsere o dados.sql, mesmo populado (pede confirmação)
  -NoSeed       nunca semeia, nem com banco vazio
  -Yes          pula a confirmação do -Reset
  -Help         imprime esta tabela

URLs depois da subida:
  http://localhost:5173   webui
  http://localhost:8080   logistic-agent
  http://localhost:8081   logistic-api
  http://localhost:8081/swagger-ui.html   Swagger
'@ | Write-Host
}

function Test-Flags {
    if ($Build -and $NoBuild) {
        Fail '-Build e -NoBuild são mutuamente exclusivos.'
    }
    if ($Reset -and $NoSeed) {
        Fail '-Reset e -NoSeed são mutuamente exclusivos.'
    }
}

# --------------------------------------------------------------------------------------
# Pré-checagens
# --------------------------------------------------------------------------------------

function Test-PortBusy {
    param([int]$Port)
    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return $null -ne $connections
}

function Test-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail 'docker não encontrado. Instale o Docker Desktop.'
    }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail 'o daemon do Docker não está rodando. Suba o Docker Desktop e tente de novo.'
    }
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "'docker compose' não disponível. Instale o plugin Compose v2."
    }
    Write-Info 'Docker ok'
}

function Test-JavaVersion {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Fail 'java não encontrado. Instale o JDK 21.'
    }

    $versionLine = (& java -version 2>&1 | Select-Object -First 1) -as [string]
    if ($versionLine -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 21) {
            Fail "Java $major encontrado; a stack precisa de Java 21 ou superior."
        }
        Write-Info "Java $major"
    }
    else {
        Write-Warn "não consegui identificar a versão do Java em: $versionLine"
    }
}

function Test-Node {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Fail 'node não encontrado. Instale o Node 20 ou superior.'
    }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Fail 'npm não encontrado. Instale o Node 20 ou superior.'
    }
    Write-Info "Node $(& node -v)"
}

function Test-Ports {
    $busy = @()
    $ports = [ordered]@{
        $DbPort    = 'Postgres'
        $ApiPort   = 'logistic-api'
        $AgentPort = 'logistic-agent'
        $WebuiPort = 'logistic-webui'
    }

    foreach ($port in $ports.Keys) {
        if (Test-PortBusy -Port ([int]$port)) {
            Write-Host "  porta $port ($($ports[$port])) já está ocupada"
            $busy += $port
        }
    }

    if ($busy.Count -gt 0) {
        Fail "libere as portas acima antes de subir. Um container de outra sessão pode estar segurando a 5432: 'docker rm -f $DbContainer'."
    }
    Write-Info 'portas 5432, 8080, 8081 e 5173 livres'
}

function Test-Llm {
    try {
        Invoke-WebRequest -Uri "$LlmUrl/v1/models" -TimeoutSec 3 -UseBasicParsing | Out-Null
        Write-Info "LLM respondendo em $LlmUrl"
    }
    catch {
        Write-Warn "LLM não respondeu em $LlmUrl — a stack sobe, mas o chat vai falhar até o modelo estar no ar."
    }
}

function Test-Prereqs {
    Write-Step 'Checando pré-requisitos'
    Test-Docker
    Test-JavaVersion
    Test-Node
    Test-Ports
    Test-Llm
}

# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

function Get-JarPath {
    param([string]$Project)
    $targetDir = Join-Path $RootDir "$Project\target"
    if (-not (Test-Path $targetDir)) {
        return $null
    }
    $jar = Get-ChildItem -Path $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-sources.jar' } |
        Select-Object -First 1
    if ($null -eq $jar) {
        return $null
    }
    return $jar.FullName
}

# Recompila se o jar não existe, ou se algum arquivo de src\ ou o pom.xml for mais novo que ele.
function Test-NeedsBuild {
    param([string]$Project)

    $jarPath = Get-JarPath -Project $Project
    if ($null -eq $jarPath) {
        return $true
    }

    $jarTime = (Get-Item $jarPath).LastWriteTime
    $pomTime = (Get-Item (Join-Path $RootDir "$Project\pom.xml")).LastWriteTime
    if ($pomTime -gt $jarTime) {
        return $true
    }

    $newer = Get-ChildItem -Path (Join-Path $RootDir "$Project\src") -Recurse -File |
        Where-Object { $_.LastWriteTime -gt $jarTime } |
        Select-Object -First 1
    return $null -ne $newer
}

function Invoke-MavenBuild {
    param([string]$Project)

    Write-Step "Compilando $Project"
    Push-Location (Join-Path $RootDir $Project)
    try {
        & .\mvnw.cmd -q -DskipTests clean package
        if ($LASTEXITCODE -ne 0) {
            Fail "falha ao compilar $Project. Rode '.\mvnw.cmd clean package' em $Project para ver o erro completo."
        }
    }
    finally {
        Pop-Location
    }
    Write-Info "$Project compilado"
}

function Test-WebuiNeedsInstall {
    $modules = Join-Path $RootDir 'logistic-webui\node_modules'
    if (-not (Test-Path $modules)) {
        return $true
    }
    $packageJson = Join-Path $RootDir 'logistic-webui\package.json'
    return (Get-Item $packageJson).LastWriteTime -gt (Get-Item $modules).LastWriteTime
}

function Invoke-WebuiInstall {
    Write-Step 'Instalando dependências do logistic-webui'
    Push-Location (Join-Path $RootDir 'logistic-webui')
    try {
        & npm install --silent
        if ($LASTEXITCODE -ne 0) {
            Fail "falha no 'npm install' do logistic-webui."
        }
    }
    finally {
        Pop-Location
    }
    Write-Info 'dependências instaladas'
}

function Invoke-BuildAll {
    if ($NoBuild) {
        Write-Step 'Build pulado (-NoBuild)'
        foreach ($project in @('logistic-api', 'logistic-agent')) {
            if ($null -eq (Get-JarPath -Project $project)) {
                Fail "-NoBuild informado, mas $project\target não tem jar. Rode sem a flag pelo menos uma vez."
            }
        }
        if (-not (Test-Path (Join-Path $RootDir 'logistic-webui\node_modules'))) {
            Fail '-NoBuild informado, mas logistic-webui\node_modules não existe.'
        }
        return
    }

    foreach ($project in @('logistic-api', 'logistic-agent')) {
        if ($Build -or (Test-NeedsBuild -Project $project)) {
            Invoke-MavenBuild -Project $project
        }
        else {
            Write-Info "$project já compilado — sem mudanças"
        }
    }

    if ($Build -or (Test-WebuiNeedsInstall)) {
        Invoke-WebuiInstall
    }
    else {
        Write-Info 'logistic-webui já instalado — sem mudanças'
    }
}

# --------------------------------------------------------------------------------------
# Espera
# --------------------------------------------------------------------------------------

function Wait-ForHttp {
    param([string]$Url, [string]$Label, [int]$TimeoutSeconds)

    $waited = 0
    Write-Host "  aguardando $Label" -NoNewline
    while ($waited -lt $TimeoutSeconds) {
        try {
            Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing | Out-Null
            Write-Host " ok ($waited`s)"
            return $true
        }
        catch {
            Start-Sleep -Seconds 2
            $waited += 2
            Write-Host '.' -NoNewline
        }
    }
    Write-Host ' falhou'
    return $false
}

function Wait-ForPort {
    param([int]$Port, [string]$Label, [int]$TimeoutSeconds)

    $waited = 0
    Write-Host "  aguardando $Label" -NoNewline
    while ($waited -lt $TimeoutSeconds) {
        if (Test-PortBusy -Port $Port) {
            Write-Host " ok ($waited`s)"
            return $true
        }
        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host '.' -NoNewline
    }
    Write-Host ' falhou'
    return $false
}

function Wait-ForPostgres {
    $waited = 0
    Write-Host '  aguardando Postgres' -NoNewline
    while ($waited -lt 60) {
        docker exec $DbContainer pg_isready -U postgres -d logisticdb *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host " ok ($waited`s)"
            return $true
        }
        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host '.' -NoNewline
    }
    Write-Host ' falhou'
    return $false
}

# --------------------------------------------------------------------------------------
# Subida
# --------------------------------------------------------------------------------------

function Start-Postgres {
    Write-Step 'Subindo Postgres'
    $script:StackStarted = $true
    docker compose -f $ComposeFile up -d
    if ($LASTEXITCODE -ne 0) {
        Fail 'falha ao subir o Postgres via docker compose.'
    }
    if (-not (Wait-ForPostgres)) {
        Fail "Postgres não ficou pronto em 60s. Veja: docker logs $DbContainer"
    }
}

function Start-BackgroundApp {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogFile)

    return Start-Process -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError "$LogFile.err" `
        -NoNewWindow -PassThru
}

function Start-Api {
    Write-Step 'Subindo logistic-api'
    $jar = Get-JarPath -Project 'logistic-api'
    $script:ApiProcess = Start-BackgroundApp -FilePath 'java' -Arguments @('-jar', $jar) `
        -WorkingDirectory (Join-Path $RootDir 'logistic-api') `
        -LogFile (Join-Path $LogDir 'logistic-api.log')

    if (-not (Wait-ForHttp -Url "http://localhost:$ApiPort/actuator/health" -Label 'logistic-api' -TimeoutSeconds 90)) {
        Fail 'logistic-api não subiu em 90s. Veja: logs\logistic-api.log'
    }
}

function Start-Agent {
    Write-Step 'Subindo logistic-agent'
    $jar = Get-JarPath -Project 'logistic-agent'
    $script:AgentProcess = Start-BackgroundApp -FilePath 'java' -Arguments @('-jar', $jar) `
        -WorkingDirectory (Join-Path $RootDir 'logistic-agent') `
        -LogFile (Join-Path $LogDir 'logistic-agent.log')

    if (-not (Wait-ForHttp -Url "http://localhost:$AgentPort/api/chat/health" -Label 'logistic-agent' -TimeoutSeconds 90)) {
        Fail 'logistic-agent não subiu em 90s. Veja: logs\logistic-agent.log'
    }
}

function Start-Webui {
    Write-Step 'Subindo logistic-webui'
    $script:WebuiProcess = Start-BackgroundApp -FilePath 'npm.cmd' -Arguments @('run', 'dev') `
        -WorkingDirectory (Join-Path $RootDir 'logistic-webui') `
        -LogFile (Join-Path $LogDir 'logistic-webui.log')

    if (-not (Wait-ForPort -Port $WebuiPort -Label 'logistic-webui' -TimeoutSeconds 60)) {
        Fail 'logistic-webui não subiu em 60s. Veja: logs\logistic-webui.log'
    }
}

# --------------------------------------------------------------------------------------
# Seed
# --------------------------------------------------------------------------------------

# Conta pela role postgres, não pela logistic_ro: essa última só tem SELECT e existe
# exclusivamente para a tool execute_query do logistic-api.
function Get-DriverCount {
    $output = docker exec $DbContainer psql -U postgres -d logisticdb -tAc 'SELECT count(*) FROM driver' 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $trimmed = ($output | Out-String).Trim()
    $parsed = 0
    if ([int]::TryParse($trimmed, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function Invoke-Seed {
    Get-Content -Path $SeedFile -Raw | docker exec -i $DbContainer psql -U postgres -d logisticdb -q
    if ($LASTEXITCODE -ne 0) {
        Fail "falha ao aplicar o seed ($SeedFile)."
    }
    Write-Info "Seed aplicado ($(Get-DriverCount) motoristas)."
}

function Confirm-Reset {
    if ($Yes) {
        return $true
    }
    $answer = Read-Host '  -Reset vai apagar TODOS os dados das tabelas e repopular. Continuar? (s/N)'
    return $answer -in @('s', 'S', 'sim', 'SIM')
}

function Initialize-SeedIfEmpty {
    Write-Step 'Verificando dados de demonstração'

    $drivers = Get-DriverCount
    if ($null -eq $drivers) {
        Fail 'não consegui contar os motoristas no banco. O Flyway rodou? Veja logs\logistic-api.log'
    }

    if ($Reset) {
        if (Confirm-Reset) {
            Write-Info 'Reset pedido — repopulando com dados de demonstração...'
            Invoke-Seed
        }
        else {
            Write-Info 'Reset cancelado — dados preservados.'
        }
        return
    }

    if ($NoSeed) {
        Write-Info "Seed desativado (-NoSeed) — banco com $drivers motoristas."
        return
    }

    if ($drivers -eq 0) {
        Write-Info 'Banco vazio — populando com dados de demonstração...'
        Invoke-Seed
    }
    else {
        Write-Info "Banco já populado ($drivers motoristas) — seed ignorado."
    }
}

# --------------------------------------------------------------------------------------
# Shutdown
# --------------------------------------------------------------------------------------

# O 'npm run dev' cria o Vite como processo filho; matar só o npm deixaria o Vite
# segurando a 5173. Por isso o Stop-Process usa -Force na árvore inteira.
function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process, [string]$Label)

    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    Write-Info "parando $Label (pid $($Process.Id))"
    try {
        taskkill /PID $Process.Id /T /F *> $null
        $Process.WaitForExit(10000) | Out-Null
    }
    catch {
        Write-Warn "não consegui parar $Label : $($_.Exception.Message)"
    }
}

function Stop-Stack {
    if ($script:ShutdownDone) {
        return
    }
    $script:ShutdownDone = $true

    Write-Step 'Derrubando a stack'

    Stop-ProcessTree -Process $script:WebuiProcess -Label 'logistic-webui'
    Stop-ProcessTree -Process $script:AgentProcess -Label 'logistic-agent'
    Stop-ProcessTree -Process $script:ApiProcess   -Label 'logistic-api'

    # 'stop' e não 'down': preserva o volume e os dados para o próximo start.
    Write-Info 'parando o Postgres (dados preservados)'
    docker compose -f $ComposeFile stop *> $null

    Write-Host ''
    Write-Host 'Stack derrubada. Dados do Postgres preservados.'
}

function Show-Urls {
    @"

================================================================
  Logistic Platform no ar

  webui      http://localhost:$WebuiPort
  agent      http://localhost:$AgentPort
  api        http://localhost:$ApiPort
  Swagger    http://localhost:$ApiPort/swagger-ui.html

  Logs em logs\ (ex.: Get-Content -Wait logs\logistic-agent.log)
  Ctrl+C derruba tudo.
================================================================
"@ | Write-Host
}

# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

if ($Help) {
    Show-Help
    exit 0
}

$exitCode = 0

try {
    Test-Flags
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

    Test-Prereqs
    Invoke-BuildAll
    Start-Postgres
    Start-Api
    Initialize-SeedIfEmpty
    Start-Agent
    Start-Webui
    Show-Urls

    # Fica em foreground até o Ctrl+C; se qualquer app morrer sozinho, derruba o resto.
    while ($true) {
        Start-Sleep -Seconds 3
        foreach ($entry in @(
                @{ Process = $script:ApiProcess;   Label = 'logistic-api' },
                @{ Process = $script:AgentProcess; Label = 'logistic-agent' },
                @{ Process = $script:WebuiProcess; Label = 'logistic-webui' })) {
            if ($null -ne $entry.Process -and $entry.Process.HasExited) {
                Write-Warn "$($entry.Label) morreu — derrubando o resto da stack. Veja logs\$($entry.Label).log"
                $exitCode = 1
                break
            }
        }
        if ($exitCode -ne 0) {
            break
        }
    }
}
catch [StackFailure] {
    Write-Host ''
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}
finally {
    # Cobre Ctrl+C, erro e saída normal. Só derruba se este script chegou a subir algo:
    # falha nas pré-checagens não pode parar um container que não é nosso.
    if ($script:StackStarted) {
        Stop-Stack
    }
}

exit $exitCode
