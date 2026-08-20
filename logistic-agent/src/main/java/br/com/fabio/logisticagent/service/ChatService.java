package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.render.RenderableContent;
import br.com.fabio.logisticagent.tool.RenderHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final ChatClient chatClient;
    private final RenderHolder renderHolder;
    private final ObjectProvider<Tracer> tracerProvider;

    public ChatService(ChatClient chatClient, RenderHolder renderHolder, ObjectProvider<Tracer> tracerProvider) {
        this.chatClient = chatClient;
        this.renderHolder = renderHolder;
        this.tracerProvider = tracerProvider;
    }

    public ChatMessageDTO respond(String userMessage, String sessionId) {
        Span span = currentSpan();
        tagRequest(span, userMessage, sessionId);

        String content = ask(userMessage, sessionId);

        for (String correction : RENDER_CORRECTIONS) {
            if (renderHolder.get() != null || !VISUAL_CLAIM.matcher(nullToEmpty(content)).find()) {
                break;
            }
            log.info("Resposta anuncia visualização sem render; refazendo com correção. sessionId={}", sessionId);
            content = ask(correction, sessionId);
        }

        tag(span, "langfuse.trace.output", content);

        RenderableContent renderData = renderHolder.get();
        return new ChatMessageDTO("assistant", withRenderFailureNotice(content, renderData), renderData);
    }

    private String ask(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
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
