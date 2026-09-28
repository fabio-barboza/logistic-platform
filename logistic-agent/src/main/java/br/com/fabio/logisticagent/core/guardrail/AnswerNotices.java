package br.com.fabio.logisticagent.core.guardrail;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.domain.render.RenderableContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

public final class AnswerNotices {

    private static final Logger log = LoggerFactory.getLogger(AnswerNotices.class);

    private static final Pattern MARKDOWN_TABLE = Pattern.compile(
            "(?m)^[ \\t]*\\|.*\\|[ \\t]*$(\\R|$)");

    private AnswerNotices() {
    }

    public static String withoutDuplicatedTable(String content, RenderableContent renderData) {
        if (renderData == null || content == null) {
            return content;
        }
        String stripped = MARKDOWN_TABLE.matcher(content).replaceAll("").replaceAll("\\R{3,}", "\n\n").strip();
        if (!stripped.equals(content.strip())) {
            log.info("Tabela markdown removida do texto: a resposta já tem render");
        }
        return stripped;
    }

    public static String withPendingActionNotice(String content, PendingAction pending) {
        if (pending == null) {
            return content;
        }
        String text = content == null ? "" : content.strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⏳ Nada foi gravado ainda. Confira os dados abaixo e confirme para executar.";
    }

    public static String withUnregisteredActionNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado. Nenhuma ação foi registrada e não há o que confirmar. "
                + "Peça a operação de novo, informando os dados necessários — e, se o seu usuário "
                + "não tem permissão de escrita, ela não vai acontecer por aqui.";
    }

    public static String withNothingWrittenNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado nesta resposta: nenhuma operação de escrita foi registrada. "
                + "Escrita só acontece depois do clique em Confirmar — e se o seu usuário não tem "
                + "permissão de escrita, ela não acontece por aqui.";
    }

    public static String withUnverifiedAnswerNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nenhuma consulta e nenhuma gravação aconteceram nesta resposta: o que está "
                + "acima não veio da plataforma. Peça de novo para eu buscar os dados — e, se era "
                + "uma operação de escrita, confirme se você tem permissão para executá-la.";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
