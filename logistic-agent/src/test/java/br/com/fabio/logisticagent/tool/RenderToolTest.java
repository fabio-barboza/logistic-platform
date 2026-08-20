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
    void renderChartWithoutDatasetsReturnsErrorAndStoresNothing() {
        String result = renderTool.renderChart("Título", "pie", List.of("SP", "RJ"), null);

        assertThat(result).contains("datasets");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void renderChartWithoutLabelsReturnsErrorAndStoresNothing() {
        String result = renderTool.renderChart("Título", "pie", List.of(),
                List.of(new Dataset("Pedidos", List.of(1, 2))));

        assertThat(result).contains("labels");
        assertThat(renderHolder.get()).isNull();
    }

    /**
     * O caso que apareceu em produção: o modelo mandou 4 rótulos para 5 status. Antes a tool
     * respondia "preparado", o modelo dizia ao usuário que o gráfico estava pronto, e o webui
     * quebrava ou desenhava um gráfico incompleto.
     */
    @Test
    void renderChartWithDataSmallerThanLabelsReturnsErrorAndStoresNothing() {
        String result = renderTool.renderChart("Pedidos por status", "pie",
                List.of("IN_ROUTE", "DELIVERED", "DELIVER_FAILURE", "COLLECTED", "CANCELED"),
                List.of(new Dataset("Pedidos", List.of(209, 1844, 737, 210))));

        assertThat(result).contains("4 valores").contains("5 rótulos");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void renderTableWithRaggedRowReturnsErrorAndStoresNothing() {
        String result = renderTool.renderTable("Entregas", List.of("Estado", "Entregas"),
                List.of(List.of("SP", "42"), List.of("RJ")));

        assertThat(result).contains("linha 2");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void renderTableWithoutRowsReturnsErrorAndStoresNothing() {
        String result = renderTool.renderTable("Entregas", List.of("Estado"), List.of());

        assertThat(result).contains("rows");
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

    @Test
    void renderChartWithMismatchedDataRecordsErrorInHolder() {
        renderTool.renderChart("Título", "bar", List.of("SP", "RJ"),
                List.of(new Dataset("Pedidos", List.of(1))));

        assertThat(renderHolder.get()).isNull();
        assertThat(renderHolder.getError()).contains("1 valores, mas labels tem 2 rótulos");
    }

    @Test
    void successfulRenderAfterRejectionClearsError() {
        renderTool.renderTable("Título", List.of("Estado"), List.of());
        assertThat(renderHolder.getError()).isNotNull();

        renderTool.renderTable("Título", List.of("Estado"), List.of(List.of("SP")));

        assertThat(renderHolder.get()).isInstanceOf(TableContent.class);
        assertThat(renderHolder.getError()).isNull();
    }

    @Test
    void secondMismatchRendersTruncatedChartInsteadOfLooping() {
        List<String> labels = List.of("SP", "RJ", "MG");
        List<Dataset> datasets = List.of(new Dataset("Pedidos", List.of(1, 2)));

        renderTool.renderChart("Título", "bar", labels, datasets);
        String result = renderTool.renderChart("Título", "bar", labels, datasets);

        assertThat(result).contains("2 primeiras categorias", "Não chame renderChart de novo");
        ChartContent chart = (ChartContent) renderHolder.get();
        assertThat(chart.labels()).containsExactly("SP", "RJ");
        assertThat(chart.datasets().getFirst().data()).containsExactly(1, 2);
        assertThat(renderHolder.getError()).isNull();
    }

    @Test
    void secondRowMismatchRendersAdjustedTableInsteadOfLooping() {
        List<String> columns = List.of("Estado", "Pedidos");
        List<List<String>> rows = List.of(List.of("SP", "42"), List.of("RJ"));

        renderTool.renderTable("Título", columns, rows);
        String result = renderTool.renderTable("Título", columns, rows);

        assertThat(result).contains("Não chame renderTable de novo");
        TableContent table = (TableContent) renderHolder.get();
        assertThat(table.rows()).containsExactly(List.of("SP", "42"), List.of("RJ", "-"));
    }

    @Test
    void repeatedUnrenderableArgumentsTellTheModelToGiveUp() {
        renderTool.renderChart("Título", "bar", List.of("SP"), List.of());
        String result = renderTool.renderChart("Título", "bar", List.of("SP"), List.of());

        assertThat(result).contains("última tentativa de render");
        assertThat(renderHolder.get()).isNull();
        assertThat(renderHolder.getError()).isNotNull();
    }

    @Test
    void renderTableAfterChartIsRefusedAndKeepsFirstVisualization() {
        renderTool.renderChart("Entregas por estado", "bar", List.of("SP", "RJ"),
                List.of(new Dataset("Entregas", List.of(42, 30))));

        String result = renderTool.renderTable("Entregas por estado", List.of("Estado", "Entregas"),
                List.of(List.of("SP", "42"), List.of("RJ", "30")));

        assertThat(result).contains("no máximo");
        assertThat(renderHolder.get()).isInstanceOf(ChartContent.class);
    }

    @Test
    void renderChartAfterTableIsRefusedAndKeepsFirstVisualization() {
        renderTool.renderTable("Entregas por estado", List.of("Estado", "Entregas"),
                List.of(List.of("SP", "42")));

        String result = renderTool.renderChart("Entregas por estado", "bar", List.of("SP"),
                List.of(new Dataset("Entregas", List.of(42))));

        assertThat(result).contains("no máximo");
        assertThat(renderHolder.get()).isInstanceOf(TableContent.class);
    }

    @Test
    void renderChartWithoutUserRequestIsIgnored() {
        renderHolder.setRenderAllowed(false);

        String result = renderTool.renderChart("Taxa de falha", "bar", List.of("SP"),
                List.of(new Dataset("Falhas", List.of(42))));

        assertThat(result).contains("não pediu");
        assertThat(renderHolder.get()).isNull();
        assertThat(renderHolder.getError()).isNull();
    }

    @Test
    void renderTableWithoutUserRequestIsIgnored() {
        renderHolder.setRenderAllowed(false);

        String result = renderTool.renderTable("Taxa de falha", List.of("Estado"), List.of(List.of("SP")));

        assertThat(result).contains("não pediu");
        assertThat(renderHolder.get()).isNull();
        assertThat(renderHolder.getError()).isNull();
    }

    /** Recusa que só repete a crítica não encerra o loop de tool calls: no teto, a chamada passa. */
    @Test
    void insistingAfterPolicyRefusalRendersAndEndsTheLoop() {
        renderHolder.setRenderAllowed(false);
        List<Dataset> datasets = List.of(new Dataset("Falhas", List.of(42)));

        String first = renderTool.renderChart("Taxa de falha", "bar", List.of("SP"), datasets);
        String second = renderTool.renderChart("Taxa de falha", "bar", List.of("SP"), datasets);

        assertThat(first).contains("não pediu");
        assertThat(second).contains("preparado");
        assertThat(renderHolder.get()).isInstanceOf(ChartContent.class);
    }

    @Test
    void insistingAfterSecondVisualizationRefusalOverwritesAndEndsTheLoop() {
        renderTool.renderChart("Falhas", "bar", List.of("SP"), List.of(new Dataset("Falhas", List.of(42))));

        String first = renderTool.renderTable("Falhas", List.of("Estado"), List.of(List.of("SP")));
        String second = renderTool.renderTable("Falhas", List.of("Estado"), List.of(List.of("SP")));

        assertThat(first).contains("no máximo uma");
        assertThat(second).contains("preparada");
        assertThat(renderHolder.get()).isInstanceOf(TableContent.class);
    }

    @Test
    void tableCellsWithStatusAreTranslatedToPortuguese() {
        renderTool.renderTable("Pedidos em SP", List.of("Cidade", "Status"),
                List.of(List.of("Campinas", "DELIVERED"), List.of("Santos", "IN_ROUTE")));

        TableContent table = (TableContent) renderHolder.get();
        assertThat(table.rows()).containsExactly(
                List.of("Campinas", "Entregue"), List.of("Santos", "Em rota"));
    }

    @Test
    void chartLabelsWithStatusAreTranslatedToPortuguese() {
        renderTool.renderChart("Pedidos por status", "bar",
                List.of("DELIVER_FAILURE", "COMPLETED_WITH_FAILURES", "SP"),
                List.of(new Dataset("Pedidos", List.of(1, 2, 3))));

        ChartContent chart = (ChartContent) renderHolder.get();
        assertThat(chart.labels())
                .containsExactly("Falha na entrega", "Concluído com falhas", "SP");
    }
}
