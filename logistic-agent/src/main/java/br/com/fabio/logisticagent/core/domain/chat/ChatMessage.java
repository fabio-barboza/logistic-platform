package br.com.fabio.logisticagent.core.domain.chat;

import br.com.fabio.logisticagent.core.domain.render.RenderableContent;

public record ChatMessage(String role, String content, RenderableContent renderData,
                          PendingAction pendingAction) {

    public ChatMessage(String role, String content, RenderableContent renderData) {
        this(role, content, renderData, null);
    }
}
