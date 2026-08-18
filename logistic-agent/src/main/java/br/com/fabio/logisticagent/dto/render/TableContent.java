package br.com.fabio.logisticagent.dto.render;

import java.util.List;

public record TableContent(
        String title,
        List<String> columns,
        List<List<String>> rows
) implements RenderableContent {
}
