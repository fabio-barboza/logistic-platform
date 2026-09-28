package br.com.fabio.logisticagent.core.domain.render;

import java.util.List;

public record ChartContent(
        String title,
        String chartType,
        List<String> labels,
        List<Dataset> datasets
) implements RenderableContent {
}
