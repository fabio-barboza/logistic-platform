// Autenticação via Keycloak com Authorization Code + PKCE, em JS puro (sem lib): o fluxo cabe
// em poucas linhas e evita uma dependência de segurança extra para manter. Se algum dia isso
// ficar mais custoso que o previsto, a alternativa aceitável é `oidc-client-ts`.

const KC_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8090'
const REALM = import.meta.env.VITE_KEYCLOAK_REALM ?? 'logistic'
const CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'logistic-webui'

const AUTH_ENDPOINT = `${KC_URL}/realms/${REALM}/protocol/openid-connect/auth`
const TOKEN_ENDPOINT = `${KC_URL}/realms/${REALM}/protocol/openid-connect/token`
const END_SESSION_ENDPOINT = `${KC_URL}/realms/${REALM}/protocol/openid-connect/logout`
const REDIRECT_URI = window.location.origin + '/'

// sessionStorage, não localStorage: sobrevive ao F5, morre ao fechar a aba — mesmo
// comportamento que o chat já tem (sessionId novo a cada load, histórico só no DOM).
const SS_VERIFIER = 'kc_pkce_verifier'
const SS_STATE = 'kc_pkce_state'
const SS_ACCESS_TOKEN = 'kc_access_token'
const SS_REFRESH_TOKEN = 'kc_refresh_token'
const SS_ID_TOKEN = 'kc_id_token'
const SS_EXPIRES_AT = 'kc_expires_at'
const SS_REDIRECT_ATTEMPTS = 'kc_redirect_attempts'

const IDLE_TIMEOUT_MS = 5 * 60 * 1000
const REFRESH_MARGIN_MS = 60 * 1000
const ACTIVITY_THROTTLE_MS = 1000
const MAX_REDIRECT_ATTEMPTS = 2

/* ---------- PKCE ---------- */

function base64UrlEncode(bytes) {
    let binary = ''
    bytes.forEach(b => { binary += String.fromCharCode(b) })
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function base64UrlDecode(str) {
    let s = str.replace(/-/g, '+').replace(/_/g, '/')
    while (s.length % 4) s += '='
    const binary = atob(s)
    const bytes = Uint8Array.from(binary, c => c.charCodeAt(0))
    return new TextDecoder().decode(bytes)
}

function randomToken(byteLength) {
    const bytes = new Uint8Array(byteLength)
    crypto.getRandomValues(bytes)
    return base64UrlEncode(bytes)
}

async function deriveCodeChallenge(verifier) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
    return base64UrlEncode(new Uint8Array(digest))
}

/* ---------- Tokens ---------- */

function setTokens({ access_token, refresh_token, id_token, expires_in }) {
    sessionStorage.setItem(SS_ACCESS_TOKEN, access_token)
    if (refresh_token) sessionStorage.setItem(SS_REFRESH_TOKEN, refresh_token)
    if (id_token) sessionStorage.setItem(SS_ID_TOKEN, id_token)
    sessionStorage.setItem(SS_EXPIRES_AT, String(Date.now() + expires_in * 1000))
}

function clearTokens() {
    [SS_ACCESS_TOKEN, SS_REFRESH_TOKEN, SS_ID_TOKEN, SS_EXPIRES_AT].forEach(k => sessionStorage.removeItem(k))
}

function hasSession() {
    return !!sessionStorage.getItem(SS_ACCESS_TOKEN)
}

/* ---------- Login ---------- */

export async function login() {
    const verifier = randomToken(64).slice(0, 128)
    const challenge = await deriveCodeChallenge(verifier)
    const state = randomToken(16)
    sessionStorage.setItem(SS_VERIFIER, verifier)
    sessionStorage.setItem(SS_STATE, state)

    const params = new URLSearchParams({
        client_id: CLIENT_ID,
        redirect_uri: REDIRECT_URI,
        response_type: 'code',
        scope: 'openid',
        state,
        code_challenge: challenge,
        code_challenge_method: 'S256',
    })
    window.location.assign(`${AUTH_ENDPOINT}?${params.toString()}`)
}

/* ---------- Logout ---------- */

export function logout() {
    const idToken = sessionStorage.getItem(SS_ID_TOKEN)
    clearTokens()
    sessionStorage.removeItem(SS_REDIRECT_ATTEMPTS)

    // Encerrar só do lado do cliente deixaria a sessão SSO viva no Keycloak — o próximo
    // login entraria sozinho, sem pedir senha.
    const params = new URLSearchParams({
        client_id: CLIENT_ID,
        post_logout_redirect_uri: REDIRECT_URI,
    })
    if (idToken) params.set('id_token_hint', idToken)
    window.location.assign(`${END_SESSION_ENDPOINT}?${params.toString()}`)
}

/* ---------- Callback (volta do Keycloak) ---------- */

function redirectAttemptsExceeded() {
    const attempts = Number(sessionStorage.getItem(SS_REDIRECT_ATTEMPTS) ?? '0') + 1
    sessionStorage.setItem(SS_REDIRECT_ATTEMPTS, String(attempts))
    return attempts > MAX_REDIRECT_ATTEMPTS
}

/**
 * Resolve o estado de autenticação ao carregar a página. Devolve `true` se há uma sessão
 * utilizável (já existente, ou trocada com sucesso a partir do `?code=` da URL) e `false`
 * quando é preciso chamar login(). Lança se o loop de redirect já tentou demais — sinal de
 * `handleCallback` falhando silenciosamente e derrubando o usuário de volta no Keycloak toda
 * vez (redirectUris do realm desalinhado, por exemplo).
 */
export async function handleCallback() {
    if (hasSession()) return true

    const url = new URL(window.location.href)
    const code = url.searchParams.get('code')
    if (!code) return false

    const returnedState = url.searchParams.get('state')
    const expectedState = sessionStorage.getItem(SS_STATE)
    const verifier = sessionStorage.getItem(SS_VERIFIER)
    sessionStorage.removeItem(SS_STATE)
    sessionStorage.removeItem(SS_VERIFIER)

    // Limpa a URL de qualquer forma: deixar code/state na barra de endereço é vazamento em
    // histórico e em referer, mesmo se a troca abaixo falhar.
    history.replaceState({}, '', window.location.pathname)

    if (!returnedState || returnedState !== expectedState || !verifier) {
        if (redirectAttemptsExceeded()) {
            throw new Error('Falha ao validar o retorno do Keycloak (state divergente) depois de várias tentativas.')
        }
        return false
    }

    try {
        const res = await fetch(TOKEN_ENDPOINT, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({
                grant_type: 'authorization_code',
                client_id: CLIENT_ID,
                code,
                redirect_uri: REDIRECT_URI,
                code_verifier: verifier,
            }),
        })
        if (!res.ok) {
            if (redirectAttemptsExceeded()) {
                throw new Error('Falha ao trocar o código de autorização por token depois de várias tentativas.')
            }
            return false
        }
        setTokens(await res.json())
        sessionStorage.removeItem(SS_REDIRECT_ATTEMPTS)
        return true
    } catch (err) {
        if (err instanceof Error && err.message.startsWith('Falha ao trocar')) throw err
        if (redirectAttemptsExceeded()) {
            throw new Error('Falha ao contatar o Keycloak para trocar o código de autorização por token.')
        }
        return false
    }
}

/* ---------- Token de acesso (renovação atrelada a atividade) ---------- */

async function refreshAccessToken() {
    const refreshToken = sessionStorage.getItem(SS_REFRESH_TOKEN)
    if (!refreshToken) {
        clearTokens()
        throw new Error('sessão sem refresh token')
    }
    const res = await fetch(TOKEN_ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            grant_type: 'refresh_token',
            client_id: CLIENT_ID,
            refresh_token: refreshToken,
        }),
    })
    if (!res.ok) {
        clearTokens()
        throw new Error('refresh token rejeitado')
    }
    setTokens(await res.json())
    return sessionStorage.getItem(SS_ACCESS_TOKEN)
}

// IMPORTANTE — NÃO transformar isto num setInterval renovando sozinho.
//
// getToken() só é chamado de dentro de authenticatedFetch(), que só roda quando o usuário faz
// alguma coisa (enviar mensagem, confirmar ação). Se o token fosse renovado num timer
// independente da atividade do usuário, o Keycloak nunca veria a sessão ociosa e o requisito
// de 5 minutos de inatividade (SSO Session Idle) deixaria de valer — a aba ficaria logada
// para sempre sozinha.
async function getToken() {
    const expiresAt = Number(sessionStorage.getItem(SS_EXPIRES_AT) ?? '0')
    if (Date.now() < expiresAt - REFRESH_MARGIN_MS) {
        return sessionStorage.getItem(SS_ACCESS_TOKEN)
    }
    return refreshAccessToken()
}

/* ---------- Ociosidade ---------- */

let idleTimer = null
let inFlightRequests = 0
let lastActivityTick = 0

function armIdleTimer() {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
        // Requisição em andamento conta como atividade: uma pergunta pode levar até 310s
        // (REQUEST_TIMEOUT_MS no main.js). Sem isso, quem faz uma pergunta pesada e tira a
        // mão do mouse é deslogado no meio da resposta.
        if (inFlightRequests > 0) {
            armIdleTimer()
            return
        }
        clearTokens()
        logout()
    }, IDLE_TIMEOUT_MS)
}

function onActivity() {
    const now = Date.now()
    if (now - lastActivityTick < ACTIVITY_THROTTLE_MS) return
    lastActivityTick = now
    armIdleTimer()
}

export function startIdleWatch() {
    ['mousemove', 'keydown', 'click', 'scroll'].forEach(evt =>
        window.addEventListener(evt, onActivity, { passive: true }))
    armIdleTimer()
}

/* ---------- Fetch autenticado ---------- */

export async function authenticatedFetch(url, options = {}) {
    inFlightRequests++
    try {
        let token
        try {
            token = await getToken()
        } catch {
            clearTokens()
            login()
            throw new Error('sessão expirada, redirecionando para login')
        }

        const headers = new Headers(options.headers ?? {})
        headers.set('Authorization', `Bearer ${token}`)
        const response = await fetch(url, { ...options, headers })

        if (response.status === 401) {
            clearTokens()
            login()
            throw new Error('sessão expirada, redirecionando para login')
        }
        return response
    } finally {
        inFlightRequests = Math.max(0, inFlightRequests - 1)
    }
}

/* ---------- Usuário atual ---------- */

// Decodifica o payload do JWT só em base64, sem validar assinatura — validação é
// responsabilidade do servidor. Aqui é só para exibir nome e roles na tela.
export function currentUser() {
    const token = sessionStorage.getItem(SS_ACCESS_TOKEN)
    if (!token) return null
    try {
        const payload = JSON.parse(base64UrlDecode(token.split('.')[1]))
        return {
            username: payload.preferred_username ?? null,
            roles: payload.realm_access?.roles ?? [],
        }
    } catch {
        return null
    }
}
