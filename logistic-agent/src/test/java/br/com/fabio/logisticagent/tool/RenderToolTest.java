package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.TableContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RenderToolTest {

    private static final String QUERY_RESULT =
            "[{\"text\":\"[{\\\"city\\\":\\\"Santos\\\",\\\"falhas\\\":7,\\\"status\\\":\\\"DELIVERED\\\"},"
                    + "{\\\"city\\\":\\\"Campinas\\\",\\\"falhas\\\":6,\\\"status\\\":\\\"DELIVER_FAILURE\\\"}]\"}]";

    private RenderHolder renderHolder;
    private QueryResultHolder queryResults;
    private RenderTool tool;

    @BeforeEach
    void setUp() {
        renderHolder = new RenderHolder();
        queryResults = new QueryResultHolder();
        tool = new RenderTool(renderHolder, queryResults);
    }

    @Test
    void chartUsesTheValuesFromTheQueryResult() {
        queryResults.register(QUERY_RESULT);

        String result = tool.renderChart("Falhas por cidade", "bar", "city", "falhas", "Falhas");

        assertThat(result).contains("2 categorias");
        ChartContent chart = (ChartContent) renderHolder.get();
        assertThat(chart.labels()).containsExactly("Santos", "Campinas");
        assertThat(chart.datasets().get(0).data()).extracting(Number::intValue).containsExactly(7, 6);
    }

    @Test
    void tableUsesTheRowsFromTheQueryResult() {
        queryResults.register(QUERY_RESULT);

        String result = tool.renderTable("Falhas", List.of("city", "falhas"));

        assertThat(result).contains("2 linhas");
        TableContent table = (TableContent) renderHolder.get();
        assertThat(table.columns()).containsExactly("city", "falhas");
        assertThat(table.rows()).containsExactly(List.of("Santos", "7"), List.of("Campinas", "6"));
    }

    @Test
    void statusIsTranslatedInTheRenderedCells() {
        queryResults.register(QUERY_RESULT);

        tool.renderTable("Status", List.of("city", "status"));

        TableContent table = (TableContent) renderHolder.get();
        assertThat(table.rows()).containsExactly(
                List.of("Santos", "Entregue"), List.of("Campinas", "Falha na entrega"));
    }

    @Test
    void unknownColumnIsRefusedWithTheAvailableOnes() {
        queryResults.register(QUERY_RESULT);

        String result = tool.renderChart("Falhas", "bar", "cidade", "falhas", "Falhas");

        assertThat(result).contains("'cidade' não existe").contains("city, falhas, status");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void renderWithoutQueryAsksForTheQueryFirst() {
        String result = tool.renderTable("Falhas", List.of("city"));

        assertThat(result).contains("Chame executeQuery antes");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void invalidChartTypeIsRefused() {
        queryResults.register(QUERY_RESULT);

        String result = tool.renderChart("Falhas", "radar", "city", "falhas", "Falhas");

        assertThat(result).contains("chartType inválido");
        assertThat(renderHolder.get()).isNull();
    }

    @Test
    void nonRowResultLeavesNothingToRender() {
        queryResults.register("[{\"text\":\"ERROR: column x does not exist\"}]");

        assertThat(queryResults.isEmpty()).isTrue();
    }
}
