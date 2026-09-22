package br.com.fabio.logisticagent.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static String sub() {
        Object auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    public static String conversationId(String sessionId) {
        String sub = sub();
        return sub == null ? sessionId : sub + "|" + sessionId;
    }
}
