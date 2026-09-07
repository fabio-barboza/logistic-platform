package br.com.fabio.logisticagent.dto;

import br.com.fabio.logisticagent.dto.render.IRenderableContent;

/**
 * @param pendingAction ação de escrita registrada nesta resposta e ainda não executada. Presente,
 *                      o frontend desenha os botões de confirmar/cancelar; ausente, nada foi
 *                      registrado.
 */
public record ChatMessageDTO(String role, String content, IRenderableContent renderData,
                             PendingActionDTO pendingAction) {

    public ChatMessageDTO(String role, String content, IRenderableContent renderData) {
        this(role, content, renderData, null);
    }
}
