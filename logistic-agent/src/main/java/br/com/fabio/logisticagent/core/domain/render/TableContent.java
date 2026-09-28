package br.com.fabio.logisticagent.core.domain.render;

import java.util.List;

public record TableContent(
        String title,
        List<String> columns,
        List<List<String>> rows
) implements RenderableContent {
}
