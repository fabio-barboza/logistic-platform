import './style.css'
import { marked } from 'marked'
import Chart from 'chart.js/auto'
import { handleCallback, login, logout, authenticatedFetch, currentUser, startIdleWatch } from './auth.js'

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/chat'
const HEALTH_URL = API_URL + '/health'
const CONFIRM_URL = API_URL + '/confirm'
// Um pouco acima do read timeout da LLM no agent (300s), para o erro do servidor chegar antes
// de o cliente desistir. Sem isso o fetch fica pendurado indefinidamente.
const REQUEST_TIMEOUT_MS = 310000
const THEME_KEY = 'lp-theme'
const SESSION_KEY = 'chat-session-id'

const CHART_COLORS = [
    'rgba(59, 130, 246, 0.85)',
    'rgba(16, 185, 129, 0.85)',
    'rgba(245, 158, 11, 0.85)',
    'rgba(239, 68, 68, 0.85)',
    'rgba(139, 92, 246, 0.85)',
    'rgba(236, 72, 153, 0.85)',
    'rgba(20, 184, 166, 0.85)',
    'rgba(249, 115, 22, 0.85)',
]

const CHART_COLORS_BORDER = CHART_COLORS.map(c => c.replace('0.85', '1'))

marked.setOptions({ breaks: true })

Chart.defaults.font.family = getComputedStyle(document.documentElement)
    .getPropertyValue('--font-sans')
    .trim()

/* ---------- Estado do agente ---------- */
//
// /api/chat/health é público (permitAll no SecurityConfig do agent) e fica fora do gate de
// autenticação abaixo, de propósito: o indicador de status funciona mesmo no instante antes
// do redirect para o login. checkAgent usa fetch puro, não authenticatedFetch — é a exceção
// parcial da regra "todo acesso à rede passa por authenticatedFetch".

const statusEl = document.getElementById('agent-status')

function setAgentStatus(online, backendOnline) {
    if (online && backendOnline) {
        statusEl.dataset.state = 'online'
        statusEl.querySelector('.status-text').textContent = 'agente online'
    } else if (online && !backendOnline) {
        statusEl.dataset.state = 'degraded'
        statusEl.querySelector('.status-text').textContent = 'backend offline'
    } else {
        statusEl.dataset.state = 'offline'
        statusEl.querySelector('.status-text').textContent = 'agente offline'
    }
}

async function checkAgent() {
    try {
        const res = await fetch(HEALTH_URL, { method: 'GET' })
        if (res.ok) {
            const data = await res.json()
            setAgentStatus(true, data.backend === 'online')
        } else {
            setAgentStatus(false, false)
        }
    } catch {
        setAgentStatus(false, false)
    }
}

checkAgent()
setInterval(checkAgent, 30000)
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) checkAgent()
})

/* ---------- Autenticação (porta da frente) ---------- */
//
// Antes de qualquer render: resolve a sessão a partir do ?code= de retorno do Keycloak (ou
// de uma sessão já existente no sessionStorage). Sem sessão utilizável, manda para o login e
// não monta o resto da aplicação — tudo daqui para baixo vive dentro de init(), chamada só
// depois desta checagem.
let authenticated
try {
    authenticated = await handleCallback()
} catch (err) {
    document.body.innerHTML = `<p style="padding:24px;font-family:sans-serif">${err.message} Recarregue a página para tentar de novo.</p>`
    throw err
}

if (!authenticated) {
    await login()
} else {
    startIdleWatch()
    init()
}

function init() {

/* ---------- Ícones ---------- */

const ICONS = {
    truck: '<path d="M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2"/><path d="M15 18H9"/><path d="M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14"/><circle cx="17" cy="18" r="2"/><circle cx="7" cy="18" r="2"/>',
    package: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    chart: '<line x1="12" x2="12" y1="20" y2="10"/><line x1="18" x2="18" y1="20" y2="4"/><line x1="6" x2="6" y1="20" y2="16"/>',
    route: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
    activity: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
    plusCircle: '<circle cx="12" cy="12" r="10"/><path d="M8 12h8"/><path d="M12 8v8"/>',
}

function icon(name, size = 18) {
    return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`
}

const LOGO_SVG = `<svg viewBox="0 0 48 48" aria-hidden="true">
  <rect width="48" height="48" rx="12" fill="#f5a524"/>
  <g transform="translate(6,6) scale(1.5)">
    <path fill="#221602" d="M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm13.5-9 1.96 2.5H17V9.5h2.5zm-1.5 9c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/>
  </g>
</svg>`

/* ---------- Tema ---------- */

const themeBtn = document.getElementById('theme-toggle')

function currentTheme() {
    return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'
}

function syncThemeButton() {
    const dark = currentTheme() === 'dark'
    themeBtn.setAttribute('aria-label', dark ? 'Mudar para tema claro' : 'Mudar para tema escuro')
    themeBtn.title = themeBtn.getAttribute('aria-label')
}

themeBtn.addEventListener('click', () => {
    const next = currentTheme() === 'dark' ? 'light' : 'dark'
    document.documentElement.dataset.theme = next
    localStorage.setItem(THEME_KEY, next)
    syncThemeButton()
    refreshCharts()
})

syncThemeButton()

/* ---------- Sessão ---------- */

function generateSessionId() {
    return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
}

// Sessão nova a cada carregamento da página, de propósito. O histórico das mensagens vive só
// no DOM e não sobrevive a um F5, mas a ChatMemory do agent é indexada pelo sessionId e
// sobrevive. Reaproveitar o id do localStorage deixava o modelo enxergando uma conversa que o
// usuário já não vê na tela — ele voltava a responder sobre o assunto anterior.
let sessionId = generateSessionId()
localStorage.setItem(SESSION_KEY, sessionId)

/* ---------- Elementos ---------- */

const chat = document.getElementById('chat')
const input = document.getElementById('input')
const sendBtn = document.getElementById('send')
const newChatBtn = document.getElementById('new-chat')
const composer = document.getElementById('composer')
const userNameEl = document.getElementById('user-name')
const logoutBtn = document.getElementById('logout-btn')

/* ---------- Usuário ---------- */

const user = currentUser()
if (user) {
    userNameEl.textContent = user.username ?? '—'
}
logoutBtn.addEventListener('click', logout)

/* ---------- Mensagens ---------- */

function scrollChat() {
    chat.scrollTop = chat.scrollHeight
}

function addUserMessage(text) {
    const div = document.createElement('div')
    div.className = 'msg user'
    const bubble = document.createElement('div')
    bubble.className = 'bubble'
    bubble.textContent = text
    div.appendChild(bubble)
    chat.appendChild(div)
    scrollChat()
}

function assistantShell() {
    const div = document.createElement('div')
    div.className = 'msg assistant'

    const avatar = document.createElement('span')
    avatar.className = 'avatar'
    avatar.innerHTML = LOGO_SVG

    const bubble = document.createElement('div')
    bubble.className = 'bubble'

    div.appendChild(avatar)
    div.appendChild(bubble)
    chat.appendChild(div)
    scrollChat()
    return bubble
}

function addLoadingMessage() {
    const bubble = assistantShell()
    const typing = document.createElement('div')
    typing.className = 'typing'
    typing.innerHTML = '<span></span><span></span><span></span>'
    bubble.appendChild(typing)
    return bubble
}

function addAssistantMessage(content, renderData, pendingAction) {
    const bubble = assistantShell()

    if (content) {
        const textDiv = document.createElement('div')
        textDiv.className = 'msg-text'
        textDiv.innerHTML = marked.parse(content)
        bubble.appendChild(textDiv)
    }

    if (renderData) {
        // Os dados de render são montados pela LLM, então podem vir incompletos (dataset sem
        // 'data', linha com menos colunas). Sem este guard, um TypeError aqui aborta o resto da
        // renderização e a mensagem fica só com o texto, sem nenhum sinal do que houve.
        try {
            if (renderData.type === 'chart') {
                bubble.appendChild(buildChart(renderData))
            } else if (renderData.type === 'table') {
                bubble.appendChild(buildTable(renderData))
            }
        } catch (err) {
            console.error('Falha ao renderizar renderData', renderData, err)
            const note = document.createElement('p')
            note.className = 'render-note'
            note.textContent = 'Não foi possível renderizar a visualização (dados incompletos). Peça novamente.'
            bubble.appendChild(note)
        }
    }

    if (pendingAction) {
        bubble.appendChild(buildPendingAction(pendingAction))
    }

    scrollChat()
}

function addErrorMessage(text) {
    const div = document.createElement('div')
    div.className = 'msg assistant error'
    const avatar = document.createElement('span')
    avatar.className = 'avatar'
    avatar.innerHTML = LOGO_SVG
    const bubble = document.createElement('div')
    bubble.className = 'bubble'
    bubble.textContent = text
    div.appendChild(avatar)
    div.appendChild(bubble)
    chat.appendChild(div)
    scrollChat()
}

/* ---------- Confirmação de escrita (human in the loop) ---------- */

// A escrita já está registrada no agent quando este card aparece: os botões só decidem se ela
// roda ou é descartada. Os argumentos vêm prontos do backend justamente para o que o usuário lê
// aqui ser o que vai ser executado.
function buildPendingAction(pending) {
    const card = document.createElement('div')
    card.className = pending.destructive ? 'confirm-card danger' : 'confirm-card'

    const title = document.createElement('p')
    title.className = 'confirm-title'
    title.textContent = pending.summary
    card.appendChild(title)

    const list = document.createElement('dl')
    list.className = 'confirm-args'
    Object.entries(pending.arguments ?? {}).forEach(([label, value]) => {
        const dt = document.createElement('dt')
        dt.textContent = label
        const dd = document.createElement('dd')
        dd.textContent = value
        list.appendChild(dt)
        list.appendChild(dd)
    })
    card.appendChild(list)

    const actions = document.createElement('div')
    actions.className = 'confirm-actions'

    const confirmBtn = document.createElement('button')
    confirmBtn.type = 'button'
    // Exclusão não tem desfazer: o botão diz o que faz, em vez de um "Confirmar" genérico.
    confirmBtn.className = pending.destructive ? 'confirm-btn danger' : 'confirm-btn primary'
    confirmBtn.textContent = pending.destructive ? 'Excluir' : 'Confirmar'

    const cancelBtn = document.createElement('button')
    cancelBtn.type = 'button'
    cancelBtn.className = 'confirm-btn'
    cancelBtn.textContent = 'Cancelar'

    const decide = async approved => {
        // Desabilita antes do await: dois cliques seriam duas execuções, e o store do agent
        // consome a pendência uma vez só — o segundo clique viraria "ação não encontrada".
        confirmBtn.disabled = true
        cancelBtn.disabled = true
        const status = document.createElement('p')
        status.className = 'confirm-status'
        status.textContent = approved ? (pending.destructive ? 'Excluindo...' : 'Executando...') : 'Cancelando...'
        card.appendChild(status)
        try {
            const response = await authenticatedFetch(CONFIRM_URL, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId, actionId: pending.id, approved }),
            })
            if (response.status === 403) {
                status.remove()
                confirmBtn.disabled = false
                cancelBtn.disabled = false
                addErrorMessage('Você não tem permissão para executar ações de escrita.')
                return
            }
            const data = await response.json()
            status.remove()
            card.dataset.resolved = approved ? 'confirmed' : 'canceled'
            actions.remove()
            addAssistantMessage(data.content, data.renderData, data.pendingAction)
        } catch {
            status.remove()
            confirmBtn.disabled = false
            cancelBtn.disabled = false
            addErrorMessage('Não foi possível enviar a confirmação. Verifique se o logistic-agent está rodando (porta 8080).')
        }
    }

    confirmBtn.addEventListener('click', () => decide(true))
    cancelBtn.addEventListener('click', () => decide(false))

    actions.appendChild(confirmBtn)
    actions.appendChild(cancelBtn)
    card.appendChild(actions)
    return card
}

/* ---------- Render de gráfico ---------- */

const chartRegistry = []

function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function chartThemeColors() {
    return {
        tick: cssVar('--chart-tick'),
        grid: cssVar('--chart-grid'),
        legend: cssVar('--chart-legend'),
    }
}

function applyChartTheme(chart) {
    const c = chartThemeColors()
    const opts = chart.options
    if (opts.plugins && opts.plugins.legend && opts.plugins.legend.labels) {
        opts.plugins.legend.labels.color = c.legend
    }
    if (opts.scales) {
        for (const key of Object.keys(opts.scales)) {
            const scale = opts.scales[key]
            if (scale.ticks) scale.ticks.color = c.tick
            if (scale.grid) scale.grid.color = c.grid
        }
    }
    chart.update('none')
}

function refreshCharts() {
    chartRegistry.forEach(applyChartTheme)
}

function destroyCharts() {
    chartRegistry.forEach(chart => chart.destroy())
    chartRegistry.length = 0
}

function buildChart(data) {
    const wrapper = document.createElement('div')
    wrapper.className = 'chart-card'

    if (data.title) {
        const title = document.createElement('p')
        title.className = 'render-title'
        title.textContent = data.title
        wrapper.appendChild(title)
    }

    const box = document.createElement('div')
    box.className = 'chart-box'
    const canvas = document.createElement('canvas')
    box.appendChild(canvas)
    wrapper.appendChild(box)

    const isPie = data.chartType === 'pie' || data.chartType === 'doughnut'
    const theme = chartThemeColors()

    const datasets = data.datasets.map((ds, i) => ({
        label: ds.label,
        data: ds.data,
        backgroundColor: isPie
            ? ds.data.map((_, j) => CHART_COLORS[j % CHART_COLORS.length])
            : CHART_COLORS[i % CHART_COLORS.length],
        borderColor: isPie
            ? ds.data.map((_, j) => CHART_COLORS_BORDER[j % CHART_COLORS_BORDER.length])
            : CHART_COLORS_BORDER[i % CHART_COLORS_BORDER.length],
        borderWidth: 1,
        borderRadius: data.chartType === 'bar' ? 4 : 0,
    }))

    const chart = new Chart(canvas, {
        type: data.chartType,
        data: { labels: data.labels, datasets },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    labels: { color: theme.legend },
                },
            },
            scales: isPie ? {} : {
                x: { ticks: { color: theme.tick }, grid: { color: theme.grid } },
                y: { ticks: { color: theme.tick }, grid: { color: theme.grid } },
            },
        },
    })

    chartRegistry.push(chart)
    return wrapper
}

/* ---------- Render de tabela ---------- */

function buildTable(data) {
    const wrapper = document.createElement('div')
    wrapper.className = 'table-card'

    if (data.title) {
        const title = document.createElement('p')
        title.className = 'render-title'
        title.style.padding = '12px 14px 0'
        title.textContent = data.title
        wrapper.appendChild(title)
    }

    const table = document.createElement('table')
    table.className = 'data-table'

    const thead = document.createElement('thead')
    const headerRow = document.createElement('tr')
    data.columns.forEach(col => {
        const th = document.createElement('th')
        th.textContent = col
        headerRow.appendChild(th)
    })
    thead.appendChild(headerRow)
    table.appendChild(thead)

    const tbody = document.createElement('tbody')
    data.rows.forEach(row => {
        const tr = document.createElement('tr')
        row.forEach(cell => {
            const td = document.createElement('td')
            td.textContent = cell ?? ''
            tr.appendChild(td)
        })
        tbody.appendChild(tr)
    })
    table.appendChild(tbody)
    wrapper.appendChild(table)

    return wrapper
}

/* ---------- Empty state ---------- */

const SUGGESTIONS = [
    { icon: 'truck', text: 'Quantos motoristas existem?' },
    { icon: 'chart', text: 'Gráfico de pedidos por status' },
    { icon: 'package', text: 'Liste 10 pedidos entregues em SP' },
    { icon: 'route', text: 'Gráfico de pizza de rotas por status' },
    { icon: 'activity', text: 'Qual a taxa de falha de entrega por estado?' },
    { icon: 'plusCircle', text: 'Cadastre um veículo chamado Truck X com capacidade 180' },
]

function renderEmptyState() {
    const chips = SUGGESTIONS.map(s => `
        <button type="button" class="chip" data-text="${s.text}">
            <span class="chip-icon">${icon(s.icon, 18)}</span>
            <span>${s.text}</span>
        </button>
    `).join('')

    chat.innerHTML = `
        <div class="empty">
            <span class="empty-logo">${LOGO_SVG}</span>
            <h1>Como posso ajudar com a sua operação?</h1>
            <p>Pergunte em linguagem natural sobre <strong>motoristas</strong>, <strong>pedidos</strong> e <strong>rotas</strong> — a IA busca os dados na plataforma e responde em texto, tabela ou gráfico.</p>
            <div class="chips">${chips}</div>
        </div>
    `

    chat.querySelectorAll('.chip').forEach(chip => {
        chip.addEventListener('click', () => sendText(chip.dataset.text))
    })
}

/* ---------- Envio ---------- */

let sending = false

function resetInputHeight() {
    input.style.height = 'auto'
}

input.addEventListener('input', () => {
    input.style.height = 'auto'
    input.style.height = Math.min(input.scrollHeight, 160) + 'px'
})

async function sendText(raw) {
    const text = (raw ?? '').trim()
    if (!text || sending) return

    sending = true
    input.value = ''
    resetInputHeight()
    sendBtn.disabled = true

    const empty = chat.querySelector('.empty')
    if (empty) empty.remove()

    addUserMessage(text)
    const loading = addLoadingMessage()

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

    try {
        const response = await authenticatedFetch(API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId, message: text }),
            signal: controller.signal,
        })

        loading.closest('.msg').remove()
        if (response.status === 403) {
            addErrorMessage('Você não tem permissão para usar o chat.')
        } else {
            const data = await response.json()
            addAssistantMessage(data.content, data.renderData, data.pendingAction)
        }
    } catch (err) {
        loading.closest('.msg').remove()
        if (err.name === 'AbortError') {
            addErrorMessage('A LLM demorou demais para responder e a requisição foi cancelada. Tente uma pergunta mais específica (filtre por cidade, período ou status) ou peça uma lista menor.')
        } else {
            addErrorMessage('Não foi possível conectar ao agente. Verifique se o logistic-agent está rodando (porta 8080) e a LLM local no ar.')
        }
    } finally {
        clearTimeout(timeoutId)
    }

    sending = false
    sendBtn.disabled = false
    input.focus()
}

function startNewChat() {
    sessionId = generateSessionId()
    localStorage.setItem(SESSION_KEY, sessionId)
    destroyCharts()
    renderEmptyState()
    input.focus()
}

composer.addEventListener('submit', e => {
    e.preventDefault()
    sendText(input.value)
})

input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendText(input.value)
    }
})

newChatBtn.addEventListener('click', startNewChat)

renderEmptyState()
input.focus()

}
