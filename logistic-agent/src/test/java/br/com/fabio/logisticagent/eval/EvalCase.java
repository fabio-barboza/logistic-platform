package br.com.fabio.logisticagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Um caso do dataset de eval.
 *
 * <p>Um caso só passa se <b>todas</b> as expectativas declaradas passarem. Campos nulos não são
 * avaliados — cada caso mede o que é específico dele, e nada mais.
 *
 * @param id          identificador curto, usado no relatório
 * @param setup       pergunta opcional feita antes, na mesma sessão (testa memória conversacional)
 * @param question    a pergunta avaliada
 * @param expectAnyOf o modelo deve chamar pelo menos uma destas tools
 * @param forbid      o modelo não pode chamar nenhuma destas tools
 * @param expectNoTool nenhuma tool MCP pode ser chamada (saudação, pergunta fora do domínio, pedido recusado)
 * @param expectArgs  trechos que devem aparecer nos argumentos das tools de {@code expectAnyOf}
 *                    (comparação sem espaços e case-insensitive, ex.: {@code "\"state\":\"SP\""});
 *                    alternativas equivalentes vão separadas por {@code ||}, e basta uma casar
 * @param forbidArgs  trechos que não podem aparecer nesses mesmos argumentos (ex.: um filtro que
 *                    distorce a contagem, como {@code "r.status="} numa pergunta sobre falha de pedido)
 * @param maxCalls    número máximo de chamadas MCP aceitas — pega o modelo que tateia até acertar
 * @param render      render esperado: "chart", "table", "none" ou null (não avaliado)
 * @param chartType   tipo de gráfico esperado quando {@code render} é "chart" (bar, line, pie, doughnut)
 * @param expectColumns colunas exatas esperadas quando o render é uma tabela, na ordem
 * @param expectText  trechos que devem aparecer na resposta final — texto e payload de render (case-insensitive)
 * @param forbidText  trechos que não podem aparecer na resposta final, texto ou render
 *                    (ex.: enum cru vazando para o usuário numa célula de tabela)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String setup,
        String question,
        List<String> expectAnyOf,
        List<String> forbid,
        Boolean expectNoTool,
        List<String> expectArgs,
        List<String> forbidArgs,
        Integer maxCalls,
        String render,
        String chartType,
        List<String> expectColumns,
        List<String> expectText,
        List<String> forbidText) {

    public List<String> expectAnyOfOrEmpty() {
        return orEmpty(expectAnyOf);
    }

    public List<String> forbidOrEmpty() {
        return orEmpty(forbid);
    }

    public List<String> expectArgsOrEmpty() {
        return orEmpty(expectArgs);
    }

    public List<String> forbidArgsOrEmpty() {
        return orEmpty(forbidArgs);
    }

    public List<String> expectColumnsOrEmpty() {
        return orEmpty(expectColumns);
    }

    public List<String> expectTextOrEmpty() {
        return orEmpty(expectText);
    }

    public List<String> forbidTextOrEmpty() {
        return orEmpty(forbidText);
    }

    public boolean expectsNoTool() {
        return Boolean.TRUE.equals(expectNoTool);
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
