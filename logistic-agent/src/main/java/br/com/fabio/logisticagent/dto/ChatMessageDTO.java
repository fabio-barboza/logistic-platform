package br.com.fabio.logisticagent.dto;

import br.com.fabio.logisticagent.dto.render.IRenderableContent;

public record ChatMessageDTO(String role, String content, IRenderableContent renderData,
                             PendingActionDTO pendingAction) {

    public ChatMessageDTO(String role, String content, IRenderableContent renderData) {
        this(role, content, renderData, null);
    }
}
