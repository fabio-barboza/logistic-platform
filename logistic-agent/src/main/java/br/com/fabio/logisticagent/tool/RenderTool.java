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
import java.util.Map;
import java.util.Set;

/**
 * Desenha gráfico ou tabela a partir do resultado do último executeQuery da requisição.
 *
 * <p><b>O modelo escolhe o que mostrar; nunca os valores.</b> Ele informa o tipo do gráfico e quais
 * colunas usar, e os números saem do {@link QueryResultHolder} — as linhas que o banco devolveu.
 * Antes o modelo digitava {@code labels} e {@code data} nos argumentos, e dado inventado na tela
 * era possível: a tool não tinha como saber se aquele 68 tinha vindo de algum lugar. Agora não é
 * detectado, é impossível.
 *
 * <p>Isso apagou junto toda a máquina de recusa que existia por causa de argumento inválido
 * (tamanho de labels diferente do de data, linha com menos células que colunas, o teto de recusas
 * e o render truncado de último recurso): sem valores nos argumentos, não há o que validar. Erro
 * de coluna continua possível e é devolvido com a lista de colunas reais, que o modelo consegue
 * corrigir numa tentativa.
 */
@Component
public class RenderTool {

    private static final Logger log = LoggerFactory.getLogger(RenderTool.class);
    private static final Set<String> VALID_CHART_TYPES = Set.of("bar", "line", "pie", "doughnut");

    /**
     * Status em PT-BR para o que vai desenhado na tela. O system prompt manda traduzir, mas o modelo
     * traduzia o texto da resposta e deixava o enum cru na tela. Tradução de enum é determinística,
     * então é código. Mantenha em sincronia com o system prompt e com V1__init.sql.
     */
    private static final Map<String, String> STATUS_PT = Map.ofEntries(
            Map.entry("IN_PROGRESS", "Em andamento"),
            Map.entry("COMPLETED", "Concluído"),
            Map.entry("COMPLETED_WITH_FAILURES", "Concluído com falhas"),
            Map.entry("CANCELED", "Cancelado"),
            Map.entry("IN_ROUTE", "Em rota"),
            Map.entry("COLLECTED", "Coletado"),
            Map.entry("DELIVERED", "Entregue"),
            Map.entry("DELIVER_FAILURE", "Falha na entrega"));

    private final RenderHolder renderHolder;
    private final QueryResultHolder queryResults;

    public RenderTool(RenderHolder renderHolder, QueryResultHolder queryResults) {
        this.renderHolder = renderHolder;
        this.queryResults = queryResults;
    }

    @Tool(description = """
            Desenha um gráfico com o resultado da última consulta que você fez por executeQuery.
            Você não envia os dados: informa quais colunas do resultado usar, e o gráfico é montado
            a partir das linhas que o banco devolveu.
            Consulte primeiro com executeQuery, depois chame esta tool com os nomes de coluna
            exatamente como aparecem no resultado (ex.: city, falhas).
            Cada resposta desenha no máximo uma visualização.
            Escolha o chartType mais adequado:
              - bar: comparação entre categorias
              - line: evolução ao longo do tempo ou sequência
              - pie: proporção de um todo (até ~6 categorias)
              - doughnut: igual ao pie, estilo diferente
            """)
    public String renderChart(
            @ToolParam(description = "Título descritivo do gráfico") String title,
            @ToolParam(description = "Tipo do gráfico: bar, line, pie ou doughnut") String chartType,
            @ToolParam(description = "Coluna do resultado usada como rótulo, ex: city") String labelColumn,
            @ToolParam(description = "Coluna numérica do resultado usada como valor, ex: falhas") String valueColumn,
            @ToolParam(description = "Legenda da série, ex: Falhas por cidade") String seriesLabel
    ) {
        if (queryResults.isEmpty()) {
            return "Nenhum resultado de consulta nesta resposta. Chame executeQuery antes de renderizar.";
        }
        if (!VALID_CHART_TYPES.contains(chartType)) {
            return "chartType inválido: '" + chartType + "'. Use bar, line, pie ou doughnut.";
        }
        String columnProblem = checkColumns(labelColumn, valueColumn);
        if (columnProblem != null) {
            return columnProblem;
        }
        List<String> labels = translateAll(queryResults.column(labelColumn));
        List<Number> values = numbers(queryResults.column(valueColumn));
        String label = seriesLabel == null || seriesLabel.isBlank() ? valueColumn : seriesLabel;
        renderHolder.set(new ChartContent(title, chartType, labels, List.of(new Dataset(label, values))));
        log.info("Gráfico preparado a partir da consulta: type={}, {} categorias", chartType, labels.size());
        return "Gráfico preparado com " + labels.size() + " categorias do resultado da consulta. "
                + "Vale só para esta resposta; para trocar o tipo, chame renderChart de novo.";
    }

    @Tool(description = """
            Desenha uma tabela com o resultado da última consulta que você fez por executeQuery.
            Você não envia os dados: informa quais colunas do resultado mostrar, na ordem desejada,
            e a tabela é montada a partir das linhas que o banco devolveu.
            Use os nomes de coluna exatamente como aparecem no resultado (ex.: name, city, falhas).
            Cada resposta desenha no máximo uma visualização.
            """)
    public String renderTable(
            @ToolParam(description = "Título da tabela") String title,
            @ToolParam(description = "Colunas do resultado a exibir, na ordem, ex: [\"city\",\"falhas\"]")
            List<String> columns
    ) {
        if (queryResults.isEmpty()) {
            return "Nenhum resultado de consulta nesta resposta. Chame executeQuery antes de renderizar.";
        }
        if (columns == null || columns.isEmpty()) {
            return "columns está vazio. Informe quais colunas do resultado mostrar. "
                    + available();
        }
        String columnProblem = checkColumns(columns.toArray(new String[0]));
        if (columnProblem != null) {
            return columnProblem;
        }
        List<List<String>> rows = queryResults.rows().stream()
                .map(row -> translateAll(columns.stream().map(c -> row.getOrDefault(c, "")).toList()))
                .toList();
        renderHolder.set(new TableContent(title, columns, rows));
        log.info("Tabela preparada a partir da consulta: {} colunas, {} linhas", columns.size(), rows.size());
        return "Tabela preparada com " + rows.size() + " linhas do resultado da consulta. "
                + "Vale só para esta resposta; para trocar as colunas, chame renderTable de novo.";
    }

    /** Coluna inexistente é o único erro de argumento que sobrou — e ele se corrige com a lista real. */
    private String checkColumns(String... names) {
        for (String name : names) {
            if (!queryResults.hasColumn(name)) {
                return "A coluna '" + name + "' não existe no resultado da consulta. " + available();
            }
        }
        return null;
    }

    private String available() {
        return "Colunas disponíveis: " + String.join(", ", queryResults.columns()) + ".";
    }

    /** Célula não numérica vira 0: o gráfico desenha, e o modelo vê pelo resultado que errou a coluna. */
    private List<Number> numbers(List<String> values) {
        return values.stream().map(value -> {
            try {
                return (Number) Double.valueOf(value.replace(",", "."));
            } catch (NumberFormatException e) {
                return (Number) 0;
            }
        }).toList();
    }

    private List<String> translateAll(List<String> values) {
        return values.stream().map(this::translateStatus).toList();
    }

    /** Só troca a célula que é exatamente um status; o resto passa intacto. */
    private String translateStatus(String value) {
        if (value == null) {
            return null;
        }
        return STATUS_PT.getOrDefault(value.strip().toUpperCase(), value);
    }
}
