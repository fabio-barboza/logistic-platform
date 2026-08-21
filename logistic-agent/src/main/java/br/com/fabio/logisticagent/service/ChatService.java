package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionMapper;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.PendingActionDTO;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.tool.RenderHolder;
import br.com.fabio.logisticagent.tool.ToolCallHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * Palavras que o modelo usa ao anunciar uma visualização. Serve só para detectar a resposta que
     * afirma um gráfico/tabela sem ter chamado a tool — o retry corretivo é disparado a partir daí.
     */
    private static final Pattern VISUAL_CLAIM = Pattern.compile(
            "gr[áa]fico|chart|tabela|pizza|rosca|donut|doughnut", Pattern.CASE_INSENSITIVE);

    /**
     * O usuário pediu uma visualização? Só então renderChart/renderTable podem desenhar (a tool
     * checa RenderHolder.isRenderAllowed). Sem isso o modelo desenhava gráfico por conta própria em
     * pergunta analítica ("qual a taxa de falha por estado?") e ainda repetia os dados em markdown.
     * Texto é o padrão; o prompt manda oferecer a visualização em vez de impor.
     */
    private static final Pattern VISUAL_REQUEST = Pattern.compile(
            "gr[áa]fic|chart|tabela|tabular|pizza|rosca|donut|doughnut|barras|linhas|"
                    + "visuali|plot|desenh|diagrama|listagem formatada",
            Pattern.CASE_INSENSITIVE);

    /**
     * Linha de tabela markdown. Quando a resposta já tem render, o modelo ainda repetia os mesmos
     * dados em markdown — a tela mostrava tabela e gráfico para uma pergunta que não pediu nenhum
     * dos dois. O prompt proíbe a duplicação; isto garante.
     */
    /**
     * Aceite curto ("sim", "pode mandar", "quero"). Vale só quando a resposta anterior ofereceu a
     * visualização — o "sim" não tem palavra nenhuma de gráfico, e sem isto ele caía no caminho de
     * render bloqueado logo depois de o próprio agente ter oferecido o gráfico.
     */
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^\\W*(sim|s|claro|ok|okay|isso|pode|podes|quero|manda|mandar|bora|beleza|blz|vai|"
                    + "aceito|mostra|mostre|faz|faça|por favor|pf)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Teto do mapa de ofertas pendentes: sessão é efêmera (novo id por load da página). */
    private static final int MAX_PENDING_OFFERS = 500;

    private static final Pattern MARKDOWN_TABLE = Pattern.compile(
            "(?m)^[ \\t]*\\|.*\\|[ \\t]*$(\\R|$)");

    /**
     * Instruções de correção, aplicadas em ordem. Duas tentativas porque a primeira, mais branda,
     * recupera a maior parte dos casos, e ainda sobram respostas em que o modelo repete a promessa
     * sem chamar tool nenhuma.
     */
    private static final List<String> RENDER_CORRECTIONS = List.of("""
            Sua resposta anterior anunciou um gráfico ou tabela, mas você não chamou renderChart nem
            renderTable — a tela do usuário ficou vazia. Refaça agora: busque os dados via tool (nunca
            use dados de memória ou inventados), chame renderChart/renderTable com esses dados e
            responda com um texto curto. Se não for o caso de renderizar nada, responda sem prometer
            gráfico ou tabela.
            """, """
            Você continua sem chamar a tool de render, e a tela do usuário segue vazia. Listar os
            dados em texto ou markdown não desenha nada. Nesta resposta, faça exatamente isto:
            chame renderChart (gráfico) ou renderTable (tabela) com os dados obtidos por tool e
            escreva no máximo uma frase depois disso, sem repetir os dados. Se não houver dados para
            renderizar, diga isso claramente e não prometa gráfico nem tabela.
            """);

    /**
     * Resposta que afirma que uma escrita está registrada e à espera do usuário.
     *
     * <p>Deliberadamente restrito a frases que afirmam a <b>existência</b> da pendência. "Preciso
     * do e-mail para registrar a ação" é o modelo pedindo dado, e está certo — não pode disparar
     * retry. "Aguardando sua confirmação" sem pendência é a tela sem botão nenhum, com o usuário
     * esperando por algo que nunca vai aparecer.
     */
    private static final Pattern ACTION_CLAIM = Pattern.compile(
            "aguard\\w*\\s+(a\\s+|sua\\s+)?confirma|"
                    + "a[çc][ãa]o\\s+(foi\\s+)?registrada|"
                    + "ser[áa]\\s+(realizada|executada|efetivada|registrada)|"
                    + "clique\\s+em\\s+confirmar|confirme\\s+(a\\s+a[çc][ãa]o|abaixo|para\\s+executar)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Correções para a resposta que anuncia uma ação pendente sem ter chamado tool de escrita
     * nenhuma. Mesmo formato do retry de render, e pelo mesmo motivo: o modelo responde de memória
     * e afirma o que não fez — aqui o custo é o usuário esperando por um botão que não existe.
     */
    private static final List<String> ACTION_CORRECTIONS = List.of("""
            Sua resposta anterior disse que a ação está registrada ou aguardando confirmação, mas
            você não chamou nenhuma tool de escrita neste turno — nada foi registrado e o usuário
            não recebeu botão nenhum na tela. Refaça agora: chame a tool de escrita correspondente
            (createDriver, createVehicle, createOrder, createRoute, updateOrderStatus,
            updateRouteStatus, linkDriverVehicle ou assignOrderToRoute) com os dados que o usuário
            já forneceu nesta conversa. Se ainda faltar algum dado obrigatório, pergunte por ele em
            vez de anunciar a ação.
            """, """
            Você continua anunciando a ação sem chamar a tool de escrita, e a tela do usuário segue
            sem o botão de confirmar. Escrever a intenção em texto não registra nada. Nesta
            resposta, chame a tool de escrita com os dados desta conversa e escreva no máximo uma
            frase depois disso. Se não for possível, diga claramente que a ação NÃO foi registrada
            e o que falta.
            """);

    /**
     * Resposta que contém dados: linha de tabela markdown, item de lista com número, ou qualquer
     * dígito solto. Usada junto com "nenhuma tool foi chamada" para detectar dado inventado — por
     * isso é ampla de propósito: um falso positivo custa um round-trip, um falso negativo entrega
     * número inventado ao usuário. Respostas de recusa ("não suportamos exclusão"), saudação e
     * off-topic não têm dígito e não disparam.
     */
    private static final Pattern DATA_CLAIM = Pattern.compile("\\d");

    /**
     * Instruções para a resposta que trouxe dados sem consultar nada. Mesmo formato do retry de
     * render: a primeira é branda, a segunda não deixa saída.
     */
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

    /** Sessões em que a última resposta ofereceu uma visualização e o usuário ainda não respondeu. */
    private final Map<String, Boolean> pendingVisualOffer = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final RenderHolder renderHolder;
    private final ToolCallHolder toolCallHolder;
    private final PendingActionHolder pendingActionHolder;
    private final PendingActionMapper pendingActionMapper;
    private final ObjectProvider<Tracer> tracerProvider;

    public ChatService(ChatClient chatClient, RenderHolder renderHolder, ToolCallHolder toolCallHolder,
                       PendingActionHolder pendingActionHolder, PendingActionMapper pendingActionMapper,
                       ObjectProvider<Tracer> tracerProvider) {
        this.chatClient = chatClient;
        this.renderHolder = renderHolder;
        this.toolCallHolder = toolCallHolder;
        this.pendingActionHolder = pendingActionHolder;
        this.pendingActionMapper = pendingActionMapper;
        this.tracerProvider = tracerProvider;
    }

    public ChatMessageDTO respond(String userMessage, String sessionId) {
        Span span = currentSpan();
        tagRequest(span, userMessage, sessionId);

        boolean renderAllowed = renderAllowed(userMessage, sessionId);
        renderHolder.setRenderAllowed(renderAllowed);
        // A tool de escrita só vê os argumentos que o modelo escreveu; o dono da pendência vem daqui.
        pendingActionHolder.setSessionId(sessionId);

        String content = ask(userMessage, sessionId);

        for (String correction : RENDER_CORRECTIONS) {
            if (!renderAllowed || renderHolder.get() != null
                    || !VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia visualização sem render; refazendo com correção. sessionId={}", sessionId);
            content = ask(correction, sessionId);
        }

        for (String correction : ACTION_CORRECTIONS) {
            if (pendingActionHolder.get() != null || !ACTION_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia ação pendente sem chamar tool de escrita; refazendo com correção. "
                    + "sessionId={}", sessionId);
            content = ask(correction, sessionId);
        }

        for (String correction : DATA_CORRECTIONS) {
            if (!answeredWithoutData(content)) {
                break;
            }
            log.info("Resposta traz dados sem nenhuma tool chamada; refazendo com correção. sessionId={}", sessionId);
            toolCallHolder.reset();
            content = ask(correction, sessionId);
        }

        tag(span, "langfuse.trace.output", content);

        RenderableContent renderData = renderHolder.get();
        rememberVisualOffer(sessionId, content, renderData);
        PendingAction pending = pendingActionHolder.get();
        String text = withPendingActionNotice(
                withRenderFailureNotice(withoutDuplicatedTable(content, renderData), renderData), pending);
        text = withUnregisteredActionNotice(text, pending);
        PendingActionDTO pendingDto = pending == null ? null : pendingActionMapper.toDto(pending);
        return new ChatMessageDTO("assistant", text, renderData, pendingDto);
    }

    /**
     * Resposta com dados que não passaram por tool nenhuma neste turno.
     * <p>
     * A checagem é sobre o turno inteiro, não sobre a pergunta: o modelo trata follow-up ("e em
     * MG?") como respondível de memória e devolve número inventado com o log de tool calls vazio.
     * Se houve render, os dados vieram do turno anterior por decisão do usuário ("transforme isso
     * num gráfico") e não há o que corrigir.
     */
    private boolean answeredWithoutData(String content) {
        return toolCallHolder.isEmpty()
                && renderHolder.get() == null
                && DATA_CLAIM.matcher(nullToEmpty(content)).find();
    }

    /**
     * Render liberado quando o usuário pede a visualização, ou quando aceita a oferta feita na
     * resposta anterior ("sim"). A oferta é consumida no mesmo ato: um "sim" só vale uma vez.
     */
    private boolean renderAllowed(String userMessage, String sessionId) {
        String message = nullToEmpty(userMessage);
        if (VISUAL_REQUEST.matcher(message).find()) {
            pendingVisualOffer.remove(sessionId);
            return true;
        }
        boolean accepted = pendingVisualOffer.remove(sessionId) != null
                && AFFIRMATIVE.matcher(message.strip()).find();
        if (accepted) {
            log.info("Render liberado: usuário aceitou a oferta de visualização. sessionId={}", sessionId);
        }
        return accepted;
    }

    /** Guarda que esta resposta ofereceu gráfico/tabela sem desenhar, para o "sim" seguinte valer. */
    private void rememberVisualOffer(String sessionId, String content, RenderableContent renderData) {
        if (renderData == null && VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
            if (pendingVisualOffer.size() >= MAX_PENDING_OFFERS) {
                pendingVisualOffer.clear();
            }
            pendingVisualOffer.put(sessionId, Boolean.TRUE);
        } else {
            pendingVisualOffer.remove(sessionId);
        }
    }

    private String ask(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    /** Com render na resposta, a mesma tabela em markdown no texto é só ruído: sai. */
    private String withoutDuplicatedTable(String content, RenderableContent renderData) {
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

    /**
     * Quando o modelo chamou renderChart/renderTable com argumentos inválidos e não refez a chamada,
     * nada foi renderizado — mas o texto costuma dizer "aqui está o gráfico". O prompt proíbe isso,
     * só que prompt não garante: o aviso aqui é o que impede a tela de mostrar uma afirmação falsa.
     */
    private String withRenderFailureNotice(String content, RenderableContent renderData) {
        String error = renderHolder.getError();
        if (renderData != null || error == null) {
            return content;
        }
        String text = content == null ? "" : content.strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi renderizado nesta resposta: a visualização foi recusada. "
                + error + " Peça de novo para eu refazer o gráfico ou a tabela.";
    }

    /**
     * Aviso de que a escrita ainda não aconteceu, quando há ação pendente.
     *
     * <p>Incondicional de propósito: a alternativa era procurar na resposta uma afirmação de
     * conclusão ("cadastrado", "pronto") e desmentir só nesses casos, mas o modelo tem infinitas
     * formas de dizer que fez, e errar para o lado de calar deixa na tela a única frase que não
     * pode estar lá. O texto do aviso é sempre verdadeiro enquanto existe pendência.
     */
    private String withPendingActionNotice(String content, PendingAction pending) {
        if (pending == null) {
            return content;
        }
        String text = content == null ? "" : content.strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⏳ Nada foi gravado ainda. Confira os dados abaixo e confirme para executar.";
    }

    /**
     * Desmente a resposta que promete confirmação sem ter registrado nada — depois de o retry
     * corretivo já ter falhado. Sem isto a tela mostra "aguardando sua confirmação" e nenhum
     * botão, e o usuário fica esperando indefinidamente por uma ação que não existe.
     */
    private String withUnregisteredActionNotice(String content, PendingAction pending) {
        if (pending != null || !ACTION_CLAIM.matcher(nullToEmpty(content)).find()) {
            return content;
        }
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nenhuma ação foi registrada e não há nada para confirmar. "
                + "Peça a operação de novo, informando os dados necessários.";
    }

    /** Span raiz da requisição, ou null quando o tracing está desligado. */
    private Span currentSpan() {
        Tracer tracer = tracerProvider.getIfAvailable();
        return tracer != null ? tracer.currentSpan() : null;
    }

    /**
     * Atributos que o Langfuse usa para nomear o trace, agrupá-lo por conversa e exibir
     * a pergunta do usuário no nível do trace.
     */
    private void tagRequest(Span span, String userMessage, String sessionId) {
        tag(span, "langfuse.trace.name", "chat");
        tag(span, "langfuse.session.id", sessionId);
        tag(span, "session.id", sessionId);
        tag(span, "langfuse.trace.input", userMessage);
    }

    private void tag(Span span, String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }
}
