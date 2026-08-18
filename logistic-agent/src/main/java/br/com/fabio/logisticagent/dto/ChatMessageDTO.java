package br.com.fabio.logisticagent.dto;

import br.com.fabio.logisticagent.dto.render.RenderableContent;

public record ChatMessageDTO(String role, String content, RenderableContent renderData) {
}
