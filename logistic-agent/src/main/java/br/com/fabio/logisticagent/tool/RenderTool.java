package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.dto.render.TableContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Provides the LLM with tools to signal that a response should be rendered
 * as a chart or table instead of plain text. Render data is stored in the
 * request-scoped RenderHolder and consumed by ChatService after the ChatClient call.
 */
@Component
public class RenderTool {

    private static final Logger log = LoggerFactory.getLogger(RenderTool.class);
    private static final Set<String> VALID_CHART_TYPES = Set.of("bar", "line", "pie", "doughnut");

    private final RenderHolder renderHolder;

    public RenderTool(RenderHolder renderHolder) {
        this.renderHolder = renderHolder;
    }

    @Tool(description = """
            Use esta tool para renderizar dados como um gráfico no frontend.
            Chame-a quando o usuário pedir um gráfico, chart ou visualização gráfica de dados numéricos.
            Escolha o chartType mais adequado para os dados:
              - bar: comparação entre categorias
              - line: evolução ao longo do tempo ou sequência
              - pie: proporção de um todo (até ~6 categorias)
              - doughnut: igual ao pie, estilo diferente
            """)
    public String renderChart(
            @ToolParam(description = "Título descritivo do gráfico") String title,
            @ToolParam(description = "Tipo do gráfico: bar, line, pie ou doughnut") String chartType,
            @ToolParam(description = "Rótulos do eixo X ou categorias, ex: [\"SP\",\"RJ\",\"MG\"]") List<String> labels,
            @ToolParam(description = "Datasets, ex: [{\"label\":\"Entregas\",\"data\":[42,30,25]}]") List<Dataset> datasets
    ) {
        if (!VALID_CHART_TYPES.contains(chartType)) {
            return "chartType inválido: '" + chartType + "'. Use bar, line, pie ou doughnut.";
        }
        renderHolder.set(new ChartContent(title, chartType, labels, datasets));
        log.info("Gráfico preparado: type={}, labels={}", chartType, labels.size());
        return "Gráfico preparado para renderização no frontend.";
    }

    @Tool(description = """
            Use esta tool para renderizar dados como uma tabela formatada no frontend.
            Chame-a quando o usuário pedir explicitamente uma tabela, listagem formatada
            ou quando os dados tabulares forem mais claros que texto corrido.
            """)
    public String renderTable(
            @ToolParam(description = "Título da tabela") String title,
            @ToolParam(description = "Nomes das colunas, ex: [\"Estado\",\"Entregas\",\"Status\"]") List<String> columns,
            @ToolParam(description = "Uma linha por registro, cada uma com um valor por coluna na ordem de "
                    + "columns. Ex: [[\"SP\",\"42\",\"DELIVERED\"],[\"RJ\",\"30\",\"IN_ROUTE\"]]") List<List<String>> rows
    ) {
        renderHolder.set(new TableContent(title, columns, rows));
        log.info("Tabela preparada: {} colunas, {} linhas", columns.size(), rows.size());
        return "Tabela preparada para renderização no frontend.";
    }
}
