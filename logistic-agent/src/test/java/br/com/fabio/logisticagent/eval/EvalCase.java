package br.com.fabio.logisticagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Um caso do dataset de eval.
 *
 * @param id          identificador curto, usado no relatório
 * @param setup       pergunta opcional feita antes, na mesma sessão (testa memória conversacional)
 * @param question    a pergunta avaliada
 * @param expectAnyOf o modelo deve chamar pelo menos uma destas tools
 * @param forbid      o modelo não pode chamar nenhuma destas tools
 * @param render      render esperado: "chart", "table", "none" ou null (não avaliado)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String setup,
        String question,
        List<String> expectAnyOf,
        List<String> forbid,
        String render) {

    public List<String> expectAnyOfOrEmpty() {
        return expectAnyOf == null ? List.of() : expectAnyOf;
    }

    public List<String> forbidOrEmpty() {
        return forbid == null ? List.of() : forbid;
    }
}
