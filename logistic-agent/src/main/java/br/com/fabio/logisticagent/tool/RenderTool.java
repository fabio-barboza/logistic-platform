package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.ChartContent;
import br.com.fabio.logisticagent.dto.render.Dataset;
import br.com.fabio.logisticagent.dto.render.TableContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Teto de recusas por requisição. A crítica devolvida como retorno de tool é o que faz o modelo
     * se corrigir, mas com temperatura baixa ele reenvia a MESMA chamada e a crítica vira um loop:
     * o loop de tool calls do Spring AI não tem limite de rodadas, e uma requisição já rodou 172
     * recusas idênticas em 26 minutos até estourar o contexto. Passado o teto, a tool para de pedir
     * correção e manda o modelo desistir do render nesta resposta.
     */
    private static final int MAX_REJECTIONS = 2;

    /**
     * Status em PT-BR para o que vai desenhado na tela. O system prompt manda traduzir, mas o modelo
     * traduz o texto da resposta e copia o enum cru para as células da tabela e os rótulos do
     * gráfico — a tela mostrava "DELIVERED" ao lado de "Entregue" na mesma resposta. Tradução de
     * enum é determinística, então é código, não instrução. Mantenha em sincronia com o system
     * prompt e com V1__init.sql.
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

    public RenderTool(RenderHolder renderHolder) {
        this.renderHolder = renderHolder;
    }

    @Tool(description = """
            Use esta tool para renderizar dados como um gráfico no frontend.
            Chame-a apenas quando o usuário pedir um gráfico, chart ou visualização gráfica de dados
            numéricos. Cada resposta desenha no máximo uma visualização: se já chamou renderTable
            nesta resposta, não chame esta.
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
        String refusal = policyRefusal("renderChart", "gráfico");
        if (refusal != null) {
            return refusal;
        }
        if (!VALID_CHART_TYPES.contains(chartType)) {
            return reject("chartType inválido: '" + chartType + "'. Use bar, line, pie ou doughnut.");
        }
        if (labels == null || labels.isEmpty()) {
            return reject("labels está vazio. Envie um rótulo por categoria, ex: [\"SP\",\"RJ\",\"MG\"].");
        }
        if (datasets == null || datasets.isEmpty()) {
            return reject("datasets está vazio. Envie ao menos uma série, ex: [{\"label\":\"Pedidos\",\"data\":[42,30,25]}].");
        }
        for (Dataset dataset : datasets) {
            if (dataset.data() == null || dataset.data().isEmpty()) {
                return reject("O dataset '" + dataset.label() + "' está sem data. Envie um número por rótulo de labels.");
            }
            if (dataset.data().size() != labels.size()) {
                String problem = "O dataset '" + dataset.label() + "' tem " + dataset.data().size()
                        + " valores, mas labels tem " + labels.size() + " rótulos.";
                if (!lastChance()) {
                    return reject(problem + " Reenvie com um valor para cada rótulo, sem omitir categorias.");
                }
                return renderTruncated(title, chartType, labels, datasets, problem);
            }
        }
        renderHolder.set(new ChartContent(title, chartType, translateAll(labels), datasets));
        log.info("Gráfico preparado: type={}, labels={}", chartType, labels.size());
        // O aviso final existe porque o modelo, ao ser pedido para trocar o tipo do gráfico,
        // respondia "aqui está em barras" sem chamar a tool de novo — e a tela ficava sem gráfico.
        return "Gráfico preparado para renderização no frontend. Vale só para esta resposta: "
                + "para trocar o tipo ou os dados, chame renderChart de novo.";
    }

    @Tool(description = """
            Use esta tool para renderizar dados como uma tabela formatada no frontend.
            Chame-a apenas quando o usuário pedir explicitamente uma tabela ou uma listagem
            formatada. Pergunta respondida por um número ou por poucas linhas de texto não
            precisa de tabela. Cada resposta desenha no máximo uma visualização: se já chamou
            renderChart nesta resposta, não chame esta.
            """)
    public String renderTable(
            @ToolParam(description = "Título da tabela") String title,
            @ToolParam(description = "Nomes das colunas, ex: [\"Estado\",\"Entregas\",\"Status\"]") List<String> columns,
            @ToolParam(description = "Uma linha por registro, cada uma com um valor por coluna na ordem de "
                    + "columns. Ex: [[\"SP\",\"42\",\"DELIVERED\"],[\"RJ\",\"30\",\"IN_ROUTE\"]]") List<List<String>> rows
    ) {
        String refusal = policyRefusal("renderTable", "tabela");
        if (refusal != null) {
            return refusal;
        }
        if (columns == null || columns.isEmpty()) {
            return reject("columns está vazio. Envie os nomes das colunas, ex: [\"Estado\",\"Entregas\"].");
        }
        if (rows == null || rows.isEmpty()) {
            return reject("rows está vazio. Envie uma linha por registro, com um valor por coluna.");
        }
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row == null || row.size() != columns.size()) {
                String problem = "A linha " + (i + 1) + " tem " + (row == null ? 0 : row.size())
                        + " valores, mas columns tem " + columns.size() + " colunas.";
                if (!lastChance()) {
                    return reject(problem + " Reenvie com um valor por coluna, na ordem de columns.");
                }
                return renderAdjusted(title, columns, rows, problem);
            }
        }
        renderHolder.set(new TableContent(title, columns, translateRows(rows)));
        log.info("Tabela preparada: {} colunas, {} linhas", columns.size(), rows.size());
        return "Tabela preparada para renderização no frontend. Vale só para esta resposta: "
                + "para trocar as colunas ou os dados, chame renderTable de novo.";
    }

    /**
     * Recusas de política — render não pedido, ou segunda visualização na mesma resposta. Devolve a
     * crítica para o modelo, ou null quando ele já insistiu demais e a chamada deve passar.
     *
     * <p>Ceder no teto não é detalhe: recusa que só repete a crítica não encerra o loop de tool calls
     * do Spring AI (que não tem limite de rodadas), e o modelo determinístico reenvia a MESMA chamada
     * — uma pergunta real rodou 182 recusas idênticas até travar. Só um retorno de sucesso encerra.
     * Quem insiste até o teto costuma ter razão: é o usuário que respondeu "sim" à oferta de gráfico
     * numa mensagem que o ChatService não reconheceu como pedido.
     */
    private String policyRefusal(String tool, String tipo) {
        if (!renderHolder.isRenderAllowed()) {
            if (yielding(tool)) {
                return null;
            }
            return ignore(tool, "O usuário não pediu " + tipo + " nesta pergunta, então a chamada foi "
                    + "ignorada e nada será desenhado na tela. Responda em texto e, se achar útil, "
                    + "ofereça a visualização (ex.: \"posso mostrar isso em gráfico, se quiser\"). "
                    + "Se o usuário já tinha pedido, chame de novo que a visualização passa.");
        }
        if (renderHolder.get() != null) {
            if (yielding(tool)) {
                return null;
            }
            return ignore(tool, "Esta resposta já tem uma visualização preparada e cada resposta desenha "
                    + "no máximo uma. A chamada foi ignorada: vale a visualização já preparada. Não chame "
                    + tool + " de novo agora — responda em texto, sem anunciar uma segunda visualização.");
        }
        return null;
    }

    /** Insistiu até o teto? Então a chamada passa — é o que encerra o loop. */
    private boolean yielding(String tool) {
        if (renderHolder.rejections() + 1 < MAX_REJECTIONS) {
            return false;
        }
        renderHolder.registerIgnored();
        log.warn("{} aceita por insistência após {} recusas de política", tool, MAX_REJECTIONS);
        return true;
    }

    private String ignore(String tool, String message) {
        int rejections = renderHolder.registerIgnored();
        log.info("{} ignorada ({}/{}): {}", tool, rejections, MAX_REJECTIONS, message);
        return message;
    }

    /** Já gastou as tentativas de correção desta requisição? Então nada de pedir outra. */
    private boolean lastChance() {
        return renderHolder.rejections() + 1 >= MAX_REJECTIONS;
    }

    /**
     * Render de último recurso quando labels e data não batem: casa os dois pelo menor tamanho e
     * desenha. Perde as categorias sobrando, mas encerra o loop — e a mensagem manda o modelo
     * avisar o usuário de que o gráfico saiu parcial.
     */
    private String renderTruncated(String title, String chartType, List<String> labels,
            List<Dataset> datasets, String problem) {
        int size = datasets.stream()
                .mapToInt(dataset -> dataset.data() == null ? 0 : dataset.data().size())
                .min()
                .orElse(0);
        size = Math.min(size, labels.size());
        if (size == 0) {
            return reject(problem + " Não há valores para desenhar. Responda ao usuário sem prometer gráfico.");
        }
        int cut = size;
        List<Dataset> trimmed = datasets.stream()
                .map(dataset -> new Dataset(dataset.label(), dataset.data().subList(0, cut)))
                .toList();
        renderHolder.set(new ChartContent(title, chartType, translateAll(labels.subList(0, cut)), trimmed));
        log.warn("Gráfico truncado após {} recusas: {} categorias de {}", MAX_REJECTIONS, cut, labels.size());
        return problem + " Depois de " + MAX_REJECTIONS + " tentativas, o gráfico foi desenhado com as "
                + cut + " primeiras categorias. Não chame renderChart de novo nesta resposta: diga ao "
                + "usuário que a visualização saiu parcial por inconsistência nos dados.";
    }

    /**
     * Mesma ideia da renderTruncated, para tabela: cada linha é cortada ou completada com "-" até
     * ter um valor por coluna.
     */
    private String renderAdjusted(String title, List<String> columns, List<List<String>> rows, String problem) {
        List<List<String>> adjusted = rows.stream().map(row -> {
            List<String> values = new ArrayList<>(row == null ? List.of() : row);
            while (values.size() < columns.size()) {
                values.add("-");
            }
            return List.copyOf(values.subList(0, columns.size()));
        }).toList();
        renderHolder.set(new TableContent(title, columns, translateRows(adjusted)));
        log.warn("Tabela ajustada após {} recusas: {} linhas normalizadas para {} colunas",
                MAX_REJECTIONS, adjusted.size(), columns.size());
        return problem + " Depois de " + MAX_REJECTIONS + " tentativas, a tabela foi desenhada com as "
                + "linhas ajustadas ao número de colunas. Não chame renderTable de novo nesta resposta: "
                + "diga ao usuário que a tabela saiu ajustada por inconsistência nos dados.";
    }

    /**
     * Registra a crítica no holder e devolve a mesma mensagem ao modelo. O holder guarda o erro
     * porque o modelo às vezes ignora a crítica e anuncia o gráfico mesmo assim — aí é o
     * ChatService que desmente a resposta.
     */
    private List<List<String>> translateRows(List<List<String>> rows) {
        return rows.stream().map(this::translateAll).toList();
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

    private String reject(String message) {
        int rejections = renderHolder.registerRejection(message);
        log.info("Render rejeitado ({}/{}): {}", rejections, MAX_REJECTIONS, message);
        if (rejections >= MAX_REJECTIONS) {
            return message + " Esta foi a última tentativa de render desta resposta: não chame "
                    + "renderChart nem renderTable de novo agora. Responda ao usuário que não foi "
                    + "possível montar a visualização com esses dados, sem prometer gráfico ou tabela.";
        }
        return message;
    }
}
