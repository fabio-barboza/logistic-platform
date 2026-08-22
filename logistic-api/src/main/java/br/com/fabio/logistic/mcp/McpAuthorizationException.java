package br.com.fabio.logistic.mcp;

/**
 * Chamador sem a role exigida por uma tool MCP. Vocabulário de scope OAuth (a spec do MCP fala em
 * scopes); o valor aqui é o nome de uma realm role do Keycloak (read/write).
 *
 * <p><b>Por que isto não vira 403 HTTP</b>, que seria o esperado. Verificado (curl direto em
 * {@code /mcp}, sem stack de mais nada no meio): o
 * {@code WebMvcStreamableServerTransportProvider} do Spring AI 2.0.0 despacha toda chamada de tool
 * — inclusive esta recusa — por {@code ServerResponse.sse(...)}, que já comitou HTTP 200 antes da
 * tool rodar. Uma exceção daqui não muda o status HTTP: vira {@code CallToolResult.isError(true)}
 * dentro da resposta 200/SSE, com esta mensagem como texto. O marcador {@code insufficient_scope}
 * no texto é o que o `logistic-agent` usa (`ChatClientConfig.toolExecutionExceptionProcessor`)
 * para distinguir esta recusa de um erro de negócio comum (ex.: motorista com rotas) e forçar o
 * loop de tool calls a parar em vez de devolver a crítica ao modelo como texto — a mesma armadilha
 * das 172 recusas de render idênticas registrada no CLAUDE.md.
 */
public class McpAuthorizationException extends RuntimeException {

    /** Substring estável que o agent procura na mensagem para reconhecer esta recusa. */
    public static final String MARKER = "insufficient_scope";

    public McpAuthorizationException(String scope) {
        super(MARKER + ": requer a role \"" + scope + "\"");
    }
}
