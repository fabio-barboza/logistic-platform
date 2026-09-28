package br.com.fabio.logisticagent.core.guardrail;

import br.com.fabio.logisticagent.core.agent.PendingActionHolder;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class WriteConfirmationGuardrail {

    private static final Logger log = LoggerFactory.getLogger(WriteConfirmationGuardrail.class);

    private static final Set<String> READ_ONLY = Set.of("executeQuery", "describeSchema");

    static final int MAX_REJECTIONS = 2;

    private final PendingActionGateway pendingActionGateway;
    private final RequiredArgumentsCheck requiredArguments;
    private final DeletionTargetLookup deletionTarget;

    public WriteConfirmationGuardrail(PendingActionGateway pendingActionGateway,
            RequiredArgumentsCheck requiredArguments, DeletionTargetLookup deletionTarget) {
        this.pendingActionGateway = pendingActionGateway;
        this.requiredArguments = requiredArguments;
        this.deletionTarget = deletionTarget;
    }

    public static boolean isWrite(String toolName) {
        return READ_ONLY.stream().noneMatch(read -> toolName.equals(read) || toolName.endsWith("_" + read));
    }

    public static String requiredRole(String toolName) {
        return isWrite(toolName) ? "write" : "read";
    }

    public String intercept(PendingActionHolder holder, String toolName, String inputSchema, String toolInput) {
        PendingAction existing = holder.get();
        if (existing != null) {
            if (existing.matches(toolName, toolInput)) {

                log.info("Chamada repetida de {} devolvida à pendência {}", toolName, existing.id());
                return registered();
            }
            return reject(holder, toolName);
        }
        List<String> missing = requiredArguments.missingFrom(toolName, inputSchema, toolInput);
        if (!missing.isEmpty()) {
            return askUserFor(holder, toolName, missing);
        }
        Map<String, String> details = Map.of();
        if (deletionTarget.supports(toolName)) {
            Optional<Map<String, String>> target = deletionTarget.describe(toolName, toolInput);
            if (target.isEmpty()) {
                return unknownTarget(holder, toolName);
            }
            details = target.get();
        }
        PendingAction action = pendingActionGateway.register(holder.sessionId(), toolName,
                toolInput == null ? "" : toolInput, details);
        holder.set(action);
        return registered();
    }

    private String registered() {
        return "Ação registrada e enviada para confirmação do usuário. NADA foi gravado ainda: "
                + "a escrita só acontece quando ele clicar em confirmar na tela. Não chame esta "
                + "tool de novo nesta resposta. Responda em uma frase, dizendo o que será feito "
                + "e que aguarda a confirmação — nunca diga que já foi feito.";
    }

    private String askUserFor(PendingActionHolder holder, String toolName, List<String> missing) {
        int rejections = holder.registerRejection();
        log.info("{} sem dados obrigatórios ({}/{}): {}", toolName, rejections, MAX_REJECTIONS, missing);
        String fields = String.join(", ", missing);
        if (rejections >= MAX_REJECTIONS) {
            return "Ainda faltam dados obrigatórios (" + fields + ") e nada foi registrado. Não "
                    + "chame mais esta tool nesta resposta: peça ao usuário, nesta mesma resposta, "
                    + "os dados que faltam e espere ele responder.";
        }
        return "Faltam dados obrigatórios para esta operação: " + fields + ". NADA foi registrado. "
                + "Pergunte esses dados ao usuário agora e só chame a tool quando ele responder — "
                + "não invente valores nem use \"N/A\".";
    }

    private String unknownTarget(PendingActionHolder holder, String toolName) {
        int rejections = holder.registerRejection();
        String entity = deletionTarget.entityOf(toolName);
        log.info("{} recusada ({}/{}): nenhum {} com o id informado",
                toolName, rejections, MAX_REJECTIONS, entity);
        if (rejections >= MAX_REJECTIONS) {
            return "Continua não existindo " + entity + " com esse id, e nada foi registrado. "
                    + "Não chame mais esta tool nesta resposta: diga ao usuário que não encontrou "
                    + "o " + entity + " e peça o nome exato.";
        }
        return "Nenhum " + entity + " com esse id. NADA foi registrado. O id precisa vir de uma "
                + "consulta executeQuery (ex.: SELECT id, name FROM driver WHERE name ILIKE "
                + "'%nome%'), nunca de memória. Consulte primeiro e chame de novo com o id "
                + "retornado; se a consulta não achar ninguém, diga isso ao usuário.";
    }

    private String reject(PendingActionHolder holder, String toolName) {
        int rejections = holder.registerRejection();
        log.info("{} recusada ({}/{}): já há ação pendente nesta resposta",
                toolName, rejections, MAX_REJECTIONS);
        if (rejections >= MAX_REJECTIONS) {
            return "Esta resposta já tem uma ação aguardando confirmação, e só é possível uma por "
                    + "vez. Não chame mais nenhuma tool de escrita agora: responda ao usuário que "
                    + "as demais alterações serão feitas depois que ele confirmar esta.";
        }
        return "Já existe uma ação aguardando confirmação nesta resposta. Cada resposta registra "
                + "uma única ação de escrita. Peça ao usuário para confirmar esta antes de seguir "
                + "para a próxima.";
    }
}
