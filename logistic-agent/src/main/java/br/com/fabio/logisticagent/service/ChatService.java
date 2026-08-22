package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionMapper;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.PendingActionDTO;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.config.ChatClientConfig;
import br.com.fabio.logisticagent.security.AuthenticatedUser;
import br.com.fabio.logisticagent.tool.RenderHolder;
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
     * Aceite curto ("sim", "pode mandar", "quero"). Vale só quando a resposta anterior ofereceu a
     * visualização — o "sim" não tem palavra nenhuma de gráfico, e sem isto ele caía no caminho de
     * render bloqueado logo depois de o próprio agente ter oferecido o gráfico.
     */
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^\\W*(sim|s|claro|ok|okay|isso|pode|podes|quero|manda|mandar|bora|beleza|blz|vai|"
                    + "aceito|mostra|mostre|faz|faça|por favor|pf)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * O usuário pediu uma escrita?
     *
     * <p>Mesma escolha do {@link #VISUAL_REQUEST}: quem enxerga a pergunta é o ChatService, e o que
     * ele decide a partir dela é código, não instrução de prompt. Isto sustenta o aviso de "nada foi
     * gravado" sem depender de como o modelo escolheu redigir a resposta — perseguir a frase do
     * modelo é corrida perdida: ele já disse "cadastrado com sucesso", "a ação foi registrada" e
     * "será cadastrado assim que você confirmar na tela" para a mesma situação, em três execuções
     * seguidas da mesma pergunta.
     *
     * <p>Só verbos de pedido. Nada de "registr" ou "inclu", que aparecem em pergunta de leitura
     * ("quantos registros existem?", "liste incluindo a cidade") e trariam o aviso para respostas
     * onde ele é ruído.
     */
    private static final Pattern WRITE_REQUEST = Pattern.compile(
            "cadastr|adicion|crie\\b|criar\\b|exclu|apag|delet|remov|atualiz|alter|edit|"
                    + "vincul|desvincul|atribu|associ",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A resposta já diz que não deu — recusa de operação inexistente ("não suportamos exclusão de
     * pedidos") ou falta de permissão.
     *
     * <p>É supressão, não detecção, e a diferença importa: se este regex falhar, sobra um aviso
     * redundante na tela; ele nunca esconde uma mentira, porque a afirmação explícita de escrita
     * ({@link #ACTION_CLAIM}) é avaliada antes e tem precedência. Sem isto, toda recusa correta
     * ganharia um aviso repetindo o que a frase já disse — e aviso que aparece sempre é aviso que
     * ninguém lê.
     */
    private static final Pattern DENIAL = Pattern.compile(
            "n[ãa]o\\s+(é|s[ãa]o|est[áa]|foi|foram)\\s+(poss[íi]vel|suportad\\w*|permitid\\w*|"
                    + "realizad\\w*|efetuad\\w*|gravad\\w*|cadastrad\\w*|exclu[íi]d\\w*|dispon[íi]ve\\w*)|"
                    + "n[ãa]o\\s+(posso|consigo|consegui|suport\\w*|tenho\\s+permiss\\w*)|"
                    + "n[ãa]o\\s+h[áa]\\s+(\\w+\\s+){0,2}(suporte|ferramenta|tool|como|permiss\\w*)|"
                    + "sem\\s+permiss\\w*|n[ãa]o\\s+\\w+\\s+(permiss[ãa]o|autoriza\\w*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Teto dos mapas por conversa: sessão é efêmera (novo id por load da página). */
    private static final int MAX_PENDING_OFFERS = 500;

    /**
     * Linha de tabela markdown. Quando a resposta já tem render, o modelo ainda repetia os mesmos
     * dados em markdown — a tela mostrava a tabela duas vezes. O prompt proíbe a duplicação; isto
     * garante.
     */
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
     * Resposta que afirma uma escrita que não existe — pendente ou já concluída.
     *
     * <p>São dois blocos. O primeiro é a promessa de confirmação, deliberadamente restrito a frases
     * que afirmam a <b>existência</b> da pendência: "preciso do e-mail para registrar a ação" é o
     * modelo pedindo dado, e está certo — não pode disparar retry. "Aguardando sua confirmação" sem
     * pendência é a tela sem botão nenhum, com o usuário esperando por algo que nunca vai aparecer.
     *
     * <p>O segundo é a afirmação de que a gravação <b>já aconteceu</b>, e ela se apoia num
     * invariante desta arquitetura: dentro de um turno do chat, escrita nunca executa — a tool
     * registra a pendência e quem grava é o {@code POST /api/chat/confirm}. Logo, "cadastrado com
     * sucesso" sem pendência no holder é sempre falso. Isso apareceu com autorização por role: sem
     * {@code write}, o usuário não recebe as tools de escrita, o modelo tenta contornar por
     * {@code executeQuery} (o {@code INSERT} morre na role read-only) e, no fim, anuncia sucesso.
     * Nada foi gravado — a fronteira é a role, não esta checagem —, mas a tela dizia o contrário.
     *
     * <p>Por isso o segundo bloco exige o marcador de conclusão ("com sucesso", ou o verbo em
     * primeira pessoa): "o motorista foi cadastrado em 12/03/2024" é leitura legítima de
     * {@code created_at}, e não pode virar desmentido.
     */
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

    /**
     * Correções para a resposta que anuncia uma ação pendente sem ter chamado tool de escrita
     * nenhuma. Mesmo formato do retry de render, e pelo mesmo motivo: o modelo responde de memória
     * e afirma o que não fez — aqui o custo é o usuário esperando por um botão que não existe.
     */
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

    /**
     * Conversas (ver {@link AuthenticatedUser#conversationId}, não sessionId cru) em que a última
     * resposta ofereceu uma visualização e o usuário ainda não respondeu.
     */
    private final Map<String, Boolean> pendingVisualOffer = new ConcurrentHashMap<>();

    /**
     * Conversas em que o pedido de escrita ainda está de pé. Existe pelo mesmo motivo do
     * {@link #pendingVisualOffer}: o "sim, pode cadastrar" (e o "sim" pelado) não repete o verbo, e
     * é justamente no turno do aceite que o modelo anuncia a gravação que não aconteceu.
     */
    private final Map<String, Boolean> pendingWriteIntent = new ConcurrentHashMap<>();

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
        // Isola a conversa por usuário autenticado: ver AuthenticatedUser.conversationId. Tudo que
        // toca ChatMemory, a oferta de visualização pendente ou o PendingActionStore usa esta
        // chave a partir daqui, nunca o sessionId cru — sessionId de outro usuário não pode
        // resolver a conversa nem a pendência dele.
        String conversationId = AuthenticatedUser.conversationId(sessionId);
        Span span = currentSpan();
        tagRequest(span, userMessage, conversationId);

        boolean renderAllowed = renderAllowed(userMessage, conversationId);
        renderHolder.setRenderAllowed(renderAllowed);
        boolean writeRequested = writeRequested(userMessage, conversationId);
        // A tool de escrita só vê os argumentos que o modelo escreveu; o dono da pendência vem daqui.
        pendingActionHolder.setSessionId(conversationId);

        try {
            return respondOrThrow(userMessage, conversationId, span, renderAllowed, writeRequested);
        } catch (ToolExecutionException e) {
            if (!isPermissionDenied(e)) {
                throw e;
            }
            log.warn("Chamada de tool recusada por falta de permissão. conversationId={}", conversationId, e);
            return new ChatMessageDTO("assistant", "Você não tem permissão para executar essa operação.", null, null);
        }
    }

    /** Marcador vindo de McpAuthorizationException (logistic-api) — ver ChatClientConfig. */
    private boolean isPermissionDenied(ToolExecutionException e) {
        Throwable cause = e.getCause();
        String message = cause != null ? cause.getMessage() : null;
        return message != null && message.contains(ChatClientConfig.PERMISSION_DENIED_MARKER);
    }

    private ChatMessageDTO respondOrThrow(String userMessage, String conversationId, Span span,
                                          boolean renderAllowed, boolean writeRequested) {
        String content = ask(userMessage, conversationId);

        for (String correction : RENDER_CORRECTIONS) {
            if (!renderAllowed || renderHolder.get() != null
                    || !VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia visualização sem render; refazendo com correção. conversationId={}", conversationId);
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

        RenderableContent renderData = renderHolder.get();
        rememberVisualOffer(conversationId, content, renderData);
        PendingAction pending = pendingActionHolder.get();
        String text = withPendingActionNotice(
                withRenderFailureNotice(withoutDuplicatedTable(content, renderData), renderData), pending);

        // Três desmentidos possíveis para a resposta que sobrou depois das correções, e eles são
        // exclusivos: dois avisos de "isso não aconteceu" na mesma resposta são ruído, e ruído faz
        // o usuário parar de ler o aviso que importa. A ordem é da afirmação mais específica para a
        // mais genérica.
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
    private boolean renderAllowed(String userMessage, String conversationId) {
        String message = nullToEmpty(userMessage);
        if (VISUAL_REQUEST.matcher(message).find()) {
            pendingVisualOffer.remove(conversationId);
            return true;
        }
        boolean accepted = pendingVisualOffer.remove(conversationId) != null
                && AFFIRMATIVE.matcher(message.strip()).find();
        if (accepted) {
            log.info("Render liberado: usuário aceitou a oferta de visualização. conversationId={}", conversationId);
        }
        return accepted;
    }

    /**
     * O turno é um pedido de escrita — direto ("cadastre o veículo X") ou como aceite de um pedido
     * que ficou de pé ("sim, pode cadastrar", ou só "sim").
     *
     * <p>O aceite precisa deste estado por conversa porque a mensagem não repete o verbo, e é
     * exatamente no turno do aceite que o modelo anuncia a gravação que não aconteceu. Uma mensagem
     * que não é escrita nem aceite fecha o assunto: o pedido antigo não pode voltar a valer três
     * perguntas depois.
     */
    private boolean writeRequested(String userMessage, String conversationId) {
        String message = nullToEmpty(userMessage).strip();
        if (WRITE_REQUEST.matcher(message).find()) {
            if (pendingWriteIntent.size() >= MAX_PENDING_OFFERS) {
                pendingWriteIntent.clear();
            }
            pendingWriteIntent.put(conversationId, Boolean.TRUE);
            return true;
        }
        if (pendingWriteIntent.containsKey(conversationId) && AFFIRMATIVE.matcher(message).find()) {
            return true;
        }
        pendingWriteIntent.remove(conversationId);
        return false;
    }

    /**
     * O usuário pediu para gravar e nenhuma pendência saiu do turno — então nada foi gravado, dê o
     * modelo a resposta que der.
     *
     * <p>Este é o caminho que sobra depois de o {@link #ACTION_CLAIM} não reconhecer a frase, e o
     * gatilho aqui não é a frase: é o pedido do usuário (código) mais a ausência de pendência
     * (fato). O caso que trouxe isto foi o usuário sem a role {@code write} — sem as tools de
     * escrita, o modelo respondeu "cadastrado com sucesso", "a ação foi registrada" e "será
     * cadastrado assim que você confirmar na tela" em três execuções da mesma pergunta. Nada foi
     * gravado nas três (a fronteira é a role na API), e a tela precisa dizer isso em todas.
     *
     * <p>Duas saídas, as duas para não gastar o aviso à toa. Pergunta: quando falta dado
     * obrigatório, o modelo pergunta, e ali o fluxo segue aberto — ninguém foi informado de que
     * algo aconteceu. Recusa ({@link #DENIAL}): a resposta já disse que não deu.
     */
    private boolean writeWentNowhere(boolean writeRequested, PendingAction pending, String content) {
        String text = nullToEmpty(content).strip();
        return writeRequested && pending == null
                && !text.endsWith("?")
                && !DENIAL.matcher(text).find();
    }

    /** Guarda que esta resposta ofereceu gráfico/tabela sem desenhar, para o "sim" seguinte valer. */
    private void rememberVisualOffer(String conversationId, String content, RenderableContent renderData) {
        if (renderData == null && VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
            if (pendingVisualOffer.size() >= MAX_PENDING_OFFERS) {
                pendingVisualOffer.clear();
            }
            pendingVisualOffer.put(conversationId, Boolean.TRUE);
        } else {
            pendingVisualOffer.remove(conversationId);
        }
    }

    private String ask(String userMessage, String conversationId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
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

    /** Resposta que promete confirmação sem que exista pendência — o retry corretivo já falhou. */
    private boolean claimsUnregisteredAction(String content, PendingAction pending) {
        return pending == null && ACTION_CLAIM.matcher(nullToEmpty(content)).find();
    }

    /**
     * Desmente a resposta que promete confirmação — ou anuncia a gravação como feita — sem que
     * exista pendência. Sem isto a tela mostra "aguardando sua confirmação" e nenhum botão, com o
     * usuário esperando indefinidamente; ou, pior, mostra "cadastrado com sucesso" para uma escrita
     * que nunca saiu do texto.
     */
    private String withUnregisteredActionNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado. Nenhuma ação foi registrada e não há o que confirmar. "
                + "Peça a operação de novo, informando os dados necessários — e, se o seu usuário "
                + "não tem permissão de escrita, ela não vai acontecer por aqui.";
    }

    /** O aviso do pedido de escrita que não virou pendência nenhuma. */
    private String withNothingWrittenNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nada foi gravado nesta resposta: nenhuma operação de escrita foi registrada. "
                + "Escrita só acontece depois do clique em Confirmar — e se o seu usuário não tem "
                + "permissão de escrita, ela não acontece por aqui.";
    }

    /**
     * Sobrou uma afirmação que nenhuma tool sustenta, depois das duas correções.
     *
     * <p>São dois desfechos com a mesma causa e o mesmo remédio. O primeiro é o dado inventado —
     * "e em MG?" respondido de memória. O segundo apareceu com autorização por role: quem não tem
     * {@code write} não recebe as tools de escrita, então o modelo não tem o que chamar e responde
     * "cadastrado com sucesso" para uma gravação que nunca existiu. Nada foi gravado (a fronteira
     * é a role na API, não esta checagem), mas a tela afirmava o contrário — e {@code ACTION_CLAIM}
     * não pega essa frase de propósito: ela fala de conclusão, não de pendência.
     *
     * <p>O gatilho é o mesmo do retry: nenhuma tool chamada no turno, resposta com dígito, nenhum
     * render. Fato binário do turno, não adivinhação sobre a frase — e se ele já custou dois
     * round-trips, também justifica um aviso quando o modelo insiste.
     *
     * <p>Pergunta fica de fora: "deseja cadastrar com capacidade de 200 kg?" tem dígito e não tem
     * tool porque o modelo está pedindo dado ao usuário, que é a resposta certa ali.
     */
    private boolean assertsUnverifiedData(String content) {
        return answeredWithoutData(content) && !nullToEmpty(content).strip().endsWith("?");
    }

    /** O aviso: o que a tela não pode deixar passar é a afirmação sem lastro. */
    private String withUnverifiedAnswerNotice(String content) {
        String text = nullToEmpty(content).strip();
        return (text.isEmpty() ? "" : text + "\n\n")
                + "> ⚠️ Nenhuma consulta e nenhuma gravação aconteceram nesta resposta: o que está "
                + "acima não veio da plataforma. Peça de novo para eu buscar os dados — e, se era "
                + "uma operação de escrita, confirme se você tem permissão para executá-la.";
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
