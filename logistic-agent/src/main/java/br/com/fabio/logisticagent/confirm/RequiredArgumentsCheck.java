package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Confere se a chamada de escrita traz todos os campos obrigatórios — antes de virar pendência.
 *
 * <p>A lista de obrigatórios sai do próprio {@code inputSchema} da tool ({@code "required": [...]}),
 * que o cliente MCP recebe da API. Nada é declarado aqui: tool nova, ou campo que deixa de ser
 * opcional, passa a ser cobrado sozinho. Uma lista mantida à mão neste arquivo envelheceria em
 * silêncio, como o SchemaText envelhece quando alguém esquece de propagar uma migration.
 *
 * <p>Por que não deixar a API recusar? Porque ela recusa <b>depois</b> da confirmação: o usuário
 * veria um card com "Nome: -", clicaria em confirmar e receberia um erro 400. Perguntar antes é o
 * ponto do human in the loop — e é o que o system prompt já pedia sem garantir.
 *
 * <p>O que isto <b>não</b> pega é o valor inventado: "cadastre o motorista João" e o modelo
 * preenche e-mail e data de nascimento plausíveis. Nenhum schema distingue isso de um dado real.
 * Quem pega é o card de confirmação, que mostra cada valor antes de gravar.
 */
@Component
public class RequiredArgumentsCheck {

    private static final Logger log = LoggerFactory.getLogger(RequiredArgumentsCheck.class);

    /**
     * Valores que o modelo usa para dizer "não sei" preenchendo o campo assim mesmo. Tratados como
     * ausência: chegariam ao banco como texto literal ("N/A" no lugar do e-mail).
     */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "-", "--", "null", "nil", "none", "n/a", "na", "string", "?",
            "desconhecido", "nao informado", "não informado", "a definir", "todo");

    private final JsonMapper jsonMapper;
    private final PendingActionMapper labels;

    public RequiredArgumentsCheck(JsonMapper jsonMapper, PendingActionMapper labels) {
        this.jsonMapper = jsonMapper;
        this.labels = labels;
    }

    /**
     * Campos obrigatórios ausentes na chamada, já com o rótulo em PT-BR do card.
     *
     * <p>Devolve vazio quando não dá para julgar — schema sem {@code required}, ou argumentos que
     * não são um objeto JSON. Nesses casos a chamada segue: recusar o que não se sabe validar
     * transformaria uma chamada correta num loop de crítica.
     */
    public List<String> missingFrom(ToolDefinition tool, String argsJson) {
        try {
            JsonNode required = jsonMapper.readTree(nullToEmptyObject(tool.inputSchema())).path("required");
            JsonNode arguments = jsonMapper.readTree(nullToEmptyObject(argsJson));
            if (!required.isArray() || !arguments.isObject()) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode field : required) {
                String name = field.asString();
                if (isBlank(arguments.path(name))) {
                    missing.add(labels.label(name));
                }
            }
            return missing;
        } catch (JacksonException e) {
            log.warn("Não foi possível checar os obrigatórios de {}: {}", tool.name(), e.getMessage());
            return List.of();
        }
    }

    private boolean isBlank(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return true;
        }
        if (!value.isString()) {
            return false;
        }
        return PLACEHOLDERS.contains(value.asString().strip().toLowerCase(Locale.ROOT));
    }

    private String nullToEmptyObject(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }
}
