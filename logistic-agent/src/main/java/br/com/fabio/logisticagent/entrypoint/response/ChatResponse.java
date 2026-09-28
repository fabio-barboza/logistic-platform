package br.com.fabio.logisticagent.entrypoint.response;

import br.com.fabio.logisticagent.core.domain.render.RenderableContent;

public record ChatResponse(String role, String content, RenderableContent renderData,
                           PendingActionResponse pendingAction) {
}
