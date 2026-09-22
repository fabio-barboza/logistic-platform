package br.com.fabio.logisticagent.confirm;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class PendingActionHolder {

    private PendingAction action;
    private int rejections;
    private String sessionId;

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public void set(PendingAction action) {
        this.action = action;
    }

    public PendingAction get() {
        return action;
    }

    public int registerRejection() {
        return ++this.rejections;
    }

    public int rejections() {
        return rejections;
    }
}
