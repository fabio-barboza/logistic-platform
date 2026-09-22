package br.com.fabio.logistic.mcp;

public class McpAuthorizationException extends RuntimeException {

    public static final String MARKER = "insufficient_scope";

    public McpAuthorizationException(String scope) {
        super(MARKER + ": requer a role \"" + scope + "\"");
    }
}
