import './style.css'
import { marked } from 'marked'
import Chart from 'chart.js/auto'

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/chat'

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

function generateSessionId() {
    return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
}

function getSessionId() {
    let sid = localStorage.getItem('chat-session-id')
    if (!sid) {
        sid = generateSessionId()
        localStorage.setItem('chat-session-id', sid)
    }
    return sid
}

let sessionId = getSessionId()
const app = document.getElementById('app')

app.innerHTML = `
<div class="app">
    <div class="header">
        <span>Logistic AI</span>
        <button id="new-chat" title="Nova conversa">+ Nova conversa</button>
    </div>
    <div class="chat" id="chat"></div>
    <div class="input-area">
        <textarea id="input" placeholder="Digite sua mensagem..."></textarea>
        <button id="send">Enviar</button>
    </div>
</div>
`

const chat = document.getElementById('chat')
const input = document.getElementById('input')
const sendBtn = document.getElementById('send')
const newChatBtn = document.getElementById('new-chat')

function addUserMessage(text) {
    const div = document.createElement('div')
    div.className = 'msg user'
    div.textContent = text
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function addLoadingMessage() {
    const div = document.createElement('div')
    div.className = 'msg assistant loading'
    div.textContent = 'Pensando...'
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function addAssistantMessage(content, renderData) {
    const div = document.createElement('div')
    div.className = 'msg assistant'

    if (content) {
        const textDiv = document.createElement('div')
        textDiv.className = 'msg-text'
        textDiv.innerHTML = marked.parse(content)
        div.appendChild(textDiv)
    }

    if (renderData) {
        if (renderData.type === 'chart') {
            div.appendChild(buildChart(renderData))
        } else if (renderData.type === 'table') {
            div.appendChild(buildTable(renderData))
        }
    }

    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function buildChart(data) {
    const wrapper = document.createElement('div')
    wrapper.className = 'chart-wrapper'

    if (data.title) {
        const title = document.createElement('p')
        title.className = 'render-title'
        title.textContent = data.title
        wrapper.appendChild(title)
    }

    const canvas = document.createElement('canvas')
    wrapper.appendChild(canvas)

    const isPie = data.chartType === 'pie' || data.chartType === 'doughnut'

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

    new Chart(canvas, {
        type: data.chartType,
        data: { labels: data.labels, datasets },
        options: {
            responsive: true,
            plugins: {
                legend: {
                    labels: { color: '#e2e8f0' },
                },
            },
            scales: isPie ? {} : {
                x: { ticks: { color: '#94a3b8' }, grid: { color: '#1e293b' } },
                y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
            },
        },
    })

    return wrapper
}

function buildTable(data) {
    const wrapper = document.createElement('div')
    wrapper.className = 'table-wrapper'

    if (data.title) {
        const title = document.createElement('p')
        title.className = 'render-title'
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

async function sendMessage() {
    const text = input.value.trim()
    if (!text) return

    input.value = ''
    sendBtn.disabled = true

    addUserMessage(text)
    const loadingMsg = addLoadingMessage()

    try {
        const response = await fetch(API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId, message: text }),
        })

        const data = await response.json()
        loadingMsg.remove()
        addAssistantMessage(data.content, data.renderData)

    } catch (err) {
        loadingMsg.className = 'msg assistant'
        loadingMsg.textContent = 'Erro ao conectar com a LLM local.'
    }

    sendBtn.disabled = false
    input.focus()
}

function startNewChat() {
    sessionId = generateSessionId()
    localStorage.setItem('chat-session-id', sessionId)
    chat.innerHTML = ''
    input.focus()
}

sendBtn.addEventListener('click', sendMessage)

input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendMessage()
    }
})

newChatBtn.addEventListener('click', startNewChat)
