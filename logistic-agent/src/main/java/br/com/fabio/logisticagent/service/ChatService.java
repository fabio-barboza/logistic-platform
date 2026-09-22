package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionMapper;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.PendingActionDTO;
import br.com.fabio.logisticagent.dto.render.IRenderableContent;
import br.com.fabio.logisticagent.config.ChatClientConfig;
import br.com.fabio.logisticagent.security.AuthenticatedUser;
import br.com.fabio.logisticagent.tool.RenderHolder;
import br.com.fabio.logisticagent.tool.RenderTool;
import br.com.fabio.logisticagent.tool.ToolCallHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^\\W*(sim|s|claro|ok|okay|isso|pode|podes|quero|manda|mandar|bora|beleza|blz|vai|"
                    + "aceito|mostra|mostre|faz|faça|por favor|pf)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WRITE_REQUEST = Pattern.compile(
            "cadastr|adicion|crie\\b|criar\\b|exclu|apag|delet|remov|atualiz|alter|edit|"
                    + "vincul|desvincul|atribu|associ",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern DENIAL = Pattern.compile(
            "n[ãa]o\\s+(é|s[ãa]o|est[áa]|foi|foram)\\s+(poss[íi]vel|suportad\\w*|permitid\\w*|"
                    + "realizad\\w*|efetuad\\w*|gravad\\w*|cadastrad\\w*|exclu[íi]d\\w*|dispon[íi]ve\\w*)|"
                    + "n[ãa]o\\s+(posso|consigo|consegui|suport\\w*|tenho\\s+permiss\\w*)|"
                    + "n[ãa]o\\s+h[áa]\\s+(\\w+\\s+){0,2}(suporte|ferramenta|tool|como|permiss\\w*)|"
                    + "sem\\s+permiss\\w*|n[ãa]o\\s+\\w+\\s+(permiss[ãa]o|autoriza\\w*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern MARKDOWN_TABLE = Pattern.compile(
            "(?m)^[ \\t]*\\|.*\\|[ \\t]*$(\\R|$)");

    private static final Pattern VISUAL_CLAIM = Pattern.compile(
            "gr[áa]fico|chart|tabela|pizza|rosca|donut|doughnut", Pattern.CASE_INSENSITIVE);

    private static final List<String> RENDER_CORRECTIONS = List.of("""
            Sua resposta anterior anunciou um gráfico ou uma tabela, mas você não chamou renderChart
            nem renderTable — a tela do usuário ficou vazia. Refaça agora: se ainda não consultou os
            dados nesta resposta, chame executeQuery, e então chame a tool de render informando as
            colunas do resultado. Depois responda com um texto curto. Se não for caso de desenhar
            nada, responda sem prometer gráfico nem tabela.
            """, """
            Você continua sem chamar a tool de render e a tela segue vazia. Listar os dados em texto
            não desenha nada. Nesta resposta faça exatamente isto: chame renderChart (gráfico) ou
            renderTable (tabela) com as colunas do resultado da consulta, e escreva no máximo uma
            frase depois. Se não houver dados para desenhar, diga isso e não prometa visualização.
            """);

    private static final Pattern ACTION_CLAIM = Pattern.compile(
            "aguard\\w*\\s+(a\\s+|sua\\s+)?confirma|"
                    + "a[çc][ãa]o\\s+(foi\\s+)?registrada|"
                    + "ser[áa]\\s+(realizada|executada|efetivada|registrada)|"
                    + "clique\\s+em\\s+confirmar|confirme\\s+(a\\s+a[çc][ãa]o|abaixo|para\\s+executar)|"
                    + "ser[áa]\\s+\\w+d[oa]s?\\b|assim\\s+que\\s+voc[êe]\\s+confirmar|"
                    + "confirmar\\s+(na\\s+tela|no\\s+card|abaixo)|"
                    + "(cadastrad|criad|exclu[íi]d|removid|atualizad|alterad|apagad|deletad|vinculad|"
                    + "atribu[íi]d|registrad|conclu[íi]d|efetuad)\\w*\\s+com\\s+sucesso|"
                    + "\\b(cadastrei|criei|exclu[íi]|removi|atualizei|alterei|apaguei|deletei|"
                    + "vinculei|atribu[íi]|registrei)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final List<String> ACTION_CORRECTIONS = List.of("""
            Sua resposta anterior disse que a ação está registrada, aguardando confirmação ou já
            concluída, mas nenhuma escrita foi registrada neste turno — nada foi gravado e o usuário
            não recebeu botão nenhum na tela. Escrita não acontece por executeQuery: ela só existe
            pelas tools de escrita, e mesmo elas apenas registram a ação para o usuário confirmar.
            Refaça agora: chame a tool de escrita correspondente
            (createDriver, createVehicle, createOrder, createRoute, updateOrderStatus,
            updateRouteStatus, linkDriverVehicle ou assignOrderToRoute) com os dados que o usuário
            já forneceu nesta conversa. Se ainda faltar algum dado obrigatório, pergunte por ele em
            vez de anunciar a ação.
            """, """
            Você continua anunciando a ação sem chamar a tool de escrita, e a tela do usuário segue
            sem o botão de confirmar. Escrever a intenção em texto não registra nada, e afirmar que
            já foi feito é pior: o usuário acredita numa gravação que não aconteceu. Nesta resposta,
            chame a tool de escrita com os dados desta conversa e escreva no máximo uma frase depois
            disso. Se a tool não estiver disponível para você, diga claramente que a operação NÃO
            foi realizada e que você não consegue executá-la — sem prometer e sem dar por feita.
            """);

    private static final Pattern DATA_CLAIM = Pattern.compile("\\d");

    private static final List<String> DATA_CORRECTIONS = List.of("""
            Sua resposta anterior apresentou dados (números, listagem ou tabela) sem que você tenha
            chamado a tool executeQuery neste turno. Esses dados não vieram do banco. Refaça agora:
            chame executeQuery com o SQL que responde exatamente à pergunta — inclusive os filtros
            que já valiam na pergunta anterior desta conversa — e responda só com o que a tool
            devolver.
            """, """
            Você respondeu de novo sem chamar executeQuery. Nada do que está na conversa anterior
            serve como fonte: os números precisam vir de uma consulta feita AGORA. Nesta resposta,
            chame executeQuery antes de escrever qualquer número. Se por algum motivo não conseguir
            montar a consulta, diga isso ao usuário e não apresente dado nenhum.
            """);

    private final IConversationStateStore conversationStateStore;

    private final ChatClient chatClient;
    private final RenderHolder renderHolder;
    private final RenderTool renderTool;
    private final ToolCallHolder toolCallHolder;
    private final PendingActionHolder pendingActionHolder;
    private final PendingActionMapper pendingActionMapper;
    private final ObjectProvider<Tracer> tracerProvider;

    public ChatService(ChatClient chatClient, RenderHolder renderHolder, RenderTool renderTool,
                       ToolCallHolder toolCallHolder,
                       PendingActionHolder pendingActionHolder, PendingActionMapper pendingActionMapper,
                       ObjectProvider<Tracer> tracerProvider, IConversationStateStore conversationStateStore) {
        this.chatClient = chatClient;
        this.renderHolder = renderHolder;
        this.renderTool = renderTool;
        this.toolCallHolder = toolCallHolder;
        this.pendingActionHolder = pendingActionHolder;
        this.pendingActionMapper = pendingActionMapper;
        this.tracerProvider = tracerProvider;
        this.conversationStateStore = conversationStateStore;
    }

    public ChatMessageDTO respond(String userMessage, String sessionId) {

        String conversationId = AuthenticatedUser.conversationId(sessionId);
        Span span = currentSpan();
        tagRequest(span, userMessage, conversationId);

        boolean writeRequested = writeRequested(userMessage, conversationId);

        pendingActionHolder.setSessionId(conversationId);

        try {
            return respondOrThrow(userMessage, conversationId, span, writeRequested);
        } catch (ToolExecutionException e) {
            if (!isPermissionDenied(e)) {
                throw e;
            }
            log.warn("Chamada de tool recusada por falta de permissão. conversationId={}", conversationId, e);
            return new ChatMessageDTO("assistant", "Você não tem permissão para executar essa operação.", null, null);
        }
    }

    private boolean isPermissionDenied(ToolExecutionException e) {
        Throwable cause = e.getCause();
        String message = cause != null ? cause.getMessage() : null;
        return message != null && message.contains(ChatClientConfig.PERMISSION_DENIED_MARKER);
    }

    private ChatMessageDTO respondOrThrow(String userMessage, String conversationId, Span span,
                                          boolean writeRequested) {
        String content = ask(userMessage, conversationId);

        for (String correction : RENDER_CORRECTIONS) {
            if (renderHolder.get() != null || !VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia visualização sem chamar render; refazendo com correção. "
                    + "conversationId={}", conversationId);
            content = ask(correction, conversationId);
        }

        for (String correction : ACTION_CORRECTIONS) {
            if (pendingActionHolder.get() != null || !ACTION_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia ação pendente sem chamar tool de escrita; refazendo com correção. "
                    + "conversationId={}", conversationId);
            content = ask(correction, conversationId);
        }

        for (String correction : DATA_CORRECTIONS) {
            if (!answeredWithoutData(content)) {
                break;
            }
            log.info("Resposta traz dados sem nenhuma tool chamada; refazendo com correção. conversationId={}",
                    conversationId);
            toolCallHolder.reset();
            content = ask(correction, conversationId);
        }

        tag(span, "langfuse.trace.output", content);

        IRenderableContent renderData = renderHolder.get();
        PendingAction pending = pendingActionHolder.get();
        String text = withPendingActionNotice(withoutDuplicatedTable(content, renderData), pending);

        if (claimsUnregisteredAction(content, pending)) {
            text = withUnregisteredActionNotice(text);
        } else if (writeWentNowhere(writeRequested, pending, content)) {
            log.warn("Pedido de escrita sem pendência registrada; avisando que nada foi gravado. "
                    + "conversationId={}", conversationId);
            text = withNothingWrittenNotice(text);
        } else if (assertsUnverifiedData(content)) {
            log.warn("Resposta sem nenhuma tool chamada depois das correções; desmentindo na tela. "
                    + "conversationId={}", conversationId);
            text = withUnverifiedAnswerNotice(text);
        }
        PendingActionDTO pendingDto = pending == null ? null : pendingActionMapper.toDto(pending);
        return new ChatMessageDTO("assistant", text, renderData, pendingDto);
    }

    private boolean answeredWithoutData(String content) {
        return toolCallHolder.isEmpty()
                && renderHolder.get() == null
                && DATA_CLAIM.matcher(nullToEmpty(content)).find();
    }

    private boolean writeRequested(String userMessage, String conversationId) {
        String message = nullToEmpty(userMessage).strip();
        if (WRITE_REQUEST.matcher(message).find()) {
            conversationStateStore.setWriteIntent(conversationId, true);
            return true;
        }
        if (conversationStateStore.hasWriteIntent(conversationId) && AFFIRMATIVE.matcher(message).find()) {
            return true;
        }
        conversationStateStore.setWriteIntent(conversationId, false);
        return false;
    }

    private boolean writeWentNowhere(boolean writeRequested, PendingAction pending, String content) {
        String text = nullToEmpty(content).strip();
        return writeRequested && pending == null
                && !text.endsWith("?")
                && !DENIAL.matcher(text).find();
    }

    private String ask(String userMessage, String conversationId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(renderTool)
                .call()
                .content();
    }

    private String withoutDuplicatedTable(String content, IRenderableContent renderData) {
        if (renderData == null || content == null) {
            return content;
        }
        String stripped = MARKDOWN_TABLE.matcher(content).replaceAll("").replaceAll("\\R{3,}", "\n\n").strip();
        if (!stripped.equals(content.strip())) {
            log.info("Tabela markdown removida do texto: a resposta já tem render");
        }
        return stripped;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String withPendingActionNotice(String content, PendingAction pending) {
        if (pending == null) {
            return content;
        }
        String text = content == null ? "" : content.strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⏳ Nada foi gravado ainda. Confira os dados abaixo e confirme para executar.";
    }

    private boolean claimsUnregisteredAction(String content, PendingAction pending) {
        return pending == null && ACTION_CLAIM.matcher(nullToEmpty(content)).find();
    }

    private String withUnregisteredActionNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado. Nenhuma ação foi registrada e não há o que confirmar. "
                + "Peça a operação de novo, informando os dados necessários — e, se o seu usuário "
                + "não tem permissão de escrita, ela não vai acontecer por aqui.";
    }

    private String withNothingWrittenNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado nesta resposta: nenhuma operação de escrita foi registrada. "
                + "Escrita só acontece depois do clique em Confirmar — e se o seu usuário não tem "
                + "permissão de escrita, ela não acontece por aqui.";
    }

    private boolean assertsUnverifiedData(String content) {
        return answeredWithoutData(content) && !nullToEmpty(content).strip().endsWith("?");
    }

    private String withUnverifiedAnswerNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nenhuma consulta e nenhuma gravação aconteceram nesta resposta: o que está "
                + "acima não veio da plataforma. Peça de novo para eu buscar os dados — e, se era "
                + "uma operação de escrita, confirme se você tem permissão para executá-la.";
    }

    private Span currentSpan() {
        Tracer tracer = tracerProvider.getIfAvailable();
        return tracer != null ? tracer.currentSpan() : null;
    }

    private void tagRequest(Span span, String userMessage, String conversationId) {
        tag(span, "langfuse.trace.name", "chat");
        tag(span, "langfuse.session.id", conversationId);
        tag(span, "session.id", conversationId);
        tag(span, "langfuse.trace.input", userMessage);
    }

    private void tag(Span span, String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }
}
