package br.com.fabio.logisticagent.confirm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Human in the loop das operações de escrita: decora as tools MCP e faz com que as de escrita
 * <b>não executem</b> na chamada do modelo — elas registram uma pendência que o usuário confirma
 * (ou cancela) na tela, e só então a tool real roda, com os argumentos originais.
 *
 * <p>A lista é por exclusão: leitura é {@code executeQuery} e {@code describeSchema}, e tudo o
 * mais que vier da API por MCP é escrita. Tool nova entra confirmada por padrão — o inverso (uma
 * lista de escrita) faria uma tool nova gravar sem confirmação até alguém lembrar de atualizar
 * esta classe.
 *
 * <p>A confirmação em si não é controle de acesso: quem chamar a API em 8081 direto, ou o /mcp
 * sem passar pelo agent, não passa por aqui. O que isto resolve é a LLM gravando por conta
 * própria a partir de uma frase ambígua.
 *
 * <p><b>{@link #getToolCallbacks()} também esconde do modelo a tool que o usuário autenticado
 * não pode usar</b> ({@code write} para escrita, {@code read} para leitura). Isto também é UX,
 * não autorização — a autorização de verdade é o {@code McpAuthorization} da logistic-api, que
 * nega a chamada mesmo que o modelo insista. Sem o filtro aqui, {@code user2} (sem {@code write})
 * pedia uma exclusão, o modelo chamava {@code deleteDriver}, a pendência era registrada e o card
 * aparecia na tela — o 403 só vinha depois do clique em confirmar, três telas para descobrir que
 * não podia. Filtrando a lista, o modelo não sabe que a tool existe e responde de cara que não
 * pode. Reduz também a superfície de prompt injection (a tool nem está no contexto), mas não
 * substitui a checagem da API: não remova aquela achando que esta basta.
 */
public class ConfirmingToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfirmingToolCallbackProvider.class);

    /** As duas únicas tools de leitura da API. O resto escreve. */
    private static final Set<String> READ_ONLY = Set.of("executeQuery", "describeSchema");

    /**
     * Teto de recusas por requisição, pelo mesmo motivo do RenderTool: retorno de tool que só
     * repete a crítica não encerra o loop de tool calls do Spring AI, e o modelo determinístico
     * reenvia a mesma chamada. Aqui não dá para "ceder e executar" no teto — ceder é exatamente o
     * que a confirmação existe para impedir —, então quem encerra o loop é o retorno idempotente:
     * repetir a MESMA chamada devolve a MESMA pendência, com cara de sucesso.
     */
    static final int MAX_REJECTIONS = 2;

    private final ToolCallbackProvider delegate;
    private final PendingActionStore store;
    private final RequiredArgumentsCheck requiredArguments;
    private final DeletionTargetLookup deletionTarget;
    private final ObjectProvider<PendingActionHolder> holderProvider;

    public ConfirmingToolCallbackProvider(ToolCallbackProvider delegate, PendingActionStore store,
            RequiredArgumentsCheck requiredArguments, DeletionTargetLookup deletionTarget,
            ObjectProvider<PendingActionHolder> holderProvider) {
        this.delegate = delegate;
        this.store = store;
        this.requiredArguments = requiredArguments;
        this.deletionTarget = deletionTarget;
        this.holderProvider = holderProvider;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] callbacks = delegate.getToolCallbacks();
        ToolCallback queryTool = readCallback(callbacks);
        return Arrays.stream(callbacks)
                .filter(this::allowed)
                .map(callback -> isWrite(callback.getToolDefinition().name())
                        ? (ToolCallback) new ConfirmingToolCallback(callback, queryTool)
                        : callback)
                .toArray(ToolCallback[]::new);
    }

    /**
     * O {@code ChatClient} resolve {@code ToolCallbackProvider} de novo a cada chamada (não uma
     * vez, no bean do builder) — verificado em {@code DefaultChatClientRequestSpec.tools}, que
     * guarda o provider e só chama {@code getToolCallbacks()} na hora de montar a requisição.
     * É isso que permite ler o usuário autenticado <b>desta</b> requisição aqui.
     *
     * <p>Sem usuário (o handshake MCP do startup do agent, fora de requisição HTTP), não filtra:
     * a lista completa é o que o agent descobre. Com usuário, esconde a tool cujo papel
     * (read/write, mesma classificação de {@link #isWrite}) ele não tem.
     */
    private boolean allowed(ToolCallback callback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken)) {
            return true;
        }
        String role = isWrite(callback.getToolDefinition().name()) ? "write" : "read";
        return auth.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    /** O `executeQuery`, usado pelo agent para descrever o alvo de uma exclusão. */
    private ToolCallback readCallback(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .filter(callback -> {
                    String name = callback.getToolDefinition().name();
                    return name.equals("executeQuery") || name.endsWith("_executeQuery");
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * O nome pode vir prefixado pelo cliente MCP (depende do gerador de nomes configurado), então
     * a comparação aceita sufixo: {@code logistic_executeQuery} continua sendo leitura.
     */
    private boolean isWrite(String toolName) {
        return READ_ONLY.stream().noneMatch(read -> toolName.equals(read) || toolName.endsWith("_" + read));
    }

    private class ConfirmingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolCallback queryTool;

        private ConfirmingToolCallback(ToolCallback delegate, ToolCallback queryTool) {
            this.delegate = delegate;
            this.queryTool = queryTool;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String toolName = getToolDefinition().name();
            PendingActionHolder holder = holder();
            if (holder == null) {
                // Fora de requisição HTTP não há tela nem usuário para confirmar — é o contexto do
                // eval, que sobe sem servlet. Exigir confirmação ali travaria o teste para sempre.
                log.warn("Sem PendingActionHolder: {} executada sem confirmação (fora de requisição)", toolName);
                return delegate.call(toolInput, toolContext);
            }
            PendingAction existing = holder.get();
            if (existing != null) {
                if (existing.matches(toolName, toolInput)) {
                    // Mesma chamada de novo: o modelo não entendeu que já estava registrada.
                    // Devolver a mesma confirmação, e não uma crítica, é o que encerra o loop.
                    log.info("Chamada repetida de {} devolvida à pendência {}", toolName, existing.id());
                    return registered();
                }
                return reject(holder, toolName);
            }
            List<String> missing = requiredArguments.missingFrom(getToolDefinition(), toolInput);
            if (!missing.isEmpty()) {
                return askUserFor(holder, toolName, missing);
            }
            Map<String, String> details = Map.of();
            if (deletionTarget.supports(toolName)) {
                Optional<Map<String, String>> target =
                        deletionTarget.describe(toolName, toolInput, queryTool);
                if (target.isEmpty()) {
                    return unknownTarget(holder, toolName);
                }
                details = target.get();
            }
            PendingAction action = store.register(holder.sessionId(), toolName,
                    toolInput == null ? "" : toolInput, delegate, details);
            holder.set(action);
            return registered();
        }

        /**
         * Retorno de sucesso: a ação foi registrada, não executada. O texto é explícito quanto a
         * isso porque o modelo, diante de qualquer retorno positivo, escreve "cadastrado com
         * sucesso" — e mesmo assim o ChatService desmente isso na tela, já que prompt não garante.
         */
        private String registered() {
            return "Ação registrada e enviada para confirmação do usuário. NADA foi gravado ainda: "
                    + "a escrita só acontece quando ele clicar em confirmar na tela. Não chame esta "
                    + "tool de novo nesta resposta. Responda em uma frase, dizendo o que será feito "
                    + "e que aguarda a confirmação — nunca diga que já foi feito.";
        }

        /**
         * Campo obrigatório faltando: nada é registrado e o modelo é mandado perguntar ao usuário.
         *
         * <p>Sem isto o modelo preenche o buraco sozinho — e o card de confirmação passa a mostrar
         * um dado que ninguém informou, que é o pior desfecho possível para uma tela cuja função é
         * o usuário conferir antes de gravar.
         */
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

        /**
         * Exclusão de um id que não existe. Acontece com UUID inventado — o modelo às vezes chama
         * deleteDriver direto, sem a consulta que o prompt manda fazer. Recusar aqui é melhor que
         * mostrar um card sem detalhe nenhum e falhar depois do clique do usuário.
         */
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

    /** Null fora de requisição HTTP — o proxy de escopo estoura ao ser tocado (ver eval). */
    private PendingActionHolder holder() {
        try {
            PendingActionHolder holder = holderProvider.getIfAvailable();
            if (holder != null) {
                holder.rejections();
            }
            return holder;
        } catch (RuntimeException e) {
            log.debug("Sem PendingActionHolder nesta execução: {}", e.getMessage());
            return null;
        }
    }
}
