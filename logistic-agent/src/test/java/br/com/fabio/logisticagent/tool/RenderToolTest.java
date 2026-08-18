package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.dto.render.TableContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RenderToolTest {

    private RenderHolder renderHolder;
    private RenderTool renderTool;

    @BeforeEach
    void setUp() {
        renderHolder = new RenderHolder();
        renderTool = new RenderTool(renderHolder);
    }

    @Test
    void renderChartWithValidArgumentsStoresChartContent() {
        List<String> labels = List.of("SP", "RJ");
        List<Dataset> datasets = List.of(new Dataset("Entregas", List.of(42, 30)));

        String result = renderTool.renderChart("Entregas por estado", "bar", labels, datasets);

        assertThat(result).contains("preparado");
        assertThat(renderHolder.get()).isInstanceOf(ChartContent.class);
        ChartContent chart = (ChartContent) renderHolder.get();
        assertThat(chart.chartType()).isEqualTo("bar");
        assertThat(chart.labels()).isEqualTo(labels);
        assertThat(chart.datasets()).isEqualTo(datasets);
    }

    @Test
    void renderChartWithInvalidChartTypeReturnsErrorAndStoresNothing() {
        String result = renderTool.renderChart("Título", "invalido", List.of("SP"), List.of());

        assertThat(result).contains("inválido");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void renderTableWithValidArgumentsStoresTableContent() {
        List<String> columns = List.of("Estado", "Entregas");
        List<List<String>> rows = List.of(List.of("SP", "42"), List.of("RJ", "30"));

        String result = renderTool.renderTable("Entregas por estado", columns, rows);

        assertThat(result).contains("preparada");
        assertThat(renderHolder.get()).isInstanceOf(TableContent.class);
        TableContent table = (TableContent) renderHolder.get();
        assertThat(table.columns()).isEqualTo(columns);
        assertThat(table.rows()).isEqualTo(rows);
    }
}
