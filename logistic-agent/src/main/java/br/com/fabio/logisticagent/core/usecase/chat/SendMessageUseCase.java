package br.com.fabio.logisticagent.core.usecase.chat;

import br.com.fabio.logisticagent.core.agent.PendingActionHolder;
import br.com.fabio.logisticagent.core.agent.RenderHolder;
import br.com.fabio.logisticagent.core.agent.ToolCallHolder;
import br.com.fabio.logisticagent.core.domain.chat.ChatMessage;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.domain.exception.PermissionDeniedException;
import br.com.fabio.logisticagent.core.domain.render.RenderableContent;
import br.com.fabio.logisticagent.core.gateway.ChatModelGateway;
import br.com.fabio.logisticagent.core.gateway.ConversationStateGateway;
import br.com.fabio.logisticagent.core.gateway.TracingGateway;
import br.com.fabio.logisticagent.core.guardrail.AnswerNotices;
import br.com.fabio.logisticagent.core.guardrail.UnbackedClaimGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageUseCase.class);

    private final ConversationStateGateway conversationStateGateway;

    private final ChatModelGateway chatModelGateway;
    private final RenderHolder renderHolder;
    private final ToolCallHolder toolCallHolder;
    private final PendingActionHolder pendingActionHolder;
    private final TracingGateway tracingGateway;

    public SendMessageUseCase(ChatModelGateway chatModelGateway, RenderHolder renderHolder,
                              ToolCallHolder toolCallHolder,
                              PendingActionHolder pendingActionHolder,
                              TracingGateway tracingGateway, ConversationStateGateway conversationStateGateway) {
        this.chatModelGateway = chatModelGateway;
        this.renderHolder = renderHolder;
        this.toolCallHolder = toolCallHolder;
        this.pendingActionHolder = pendingActionHolder;
        this.tracingGateway = tracingGateway;
        this.conversationStateGateway = conversationStateGateway;
    }

    public ChatMessage respond(String userMessage, String conversationId) {
        tagRequest(userMessage, conversationId);

        boolean writeRequested = writeRequested(userMessage, conversationId);

        pendingActionHolder.setSessionId(conversationId);

        try {
            return respondOrThrow(userMessage, conversationId, writeRequested);
        } catch (PermissionDeniedException e) {
            log.warn("Chamada de tool recusada por falta de permissão. conversationId={}", conversationId, e);
            return new ChatMessage("assistant", "Você não tem permissão para executar essa operação.", null, null);
        }
    }

    private ChatMessage respondOrThrow(String userMessage, String conversationId,
                                          boolean writeRequested) {
        String content = ask(userMessage, conversationId);

        for (String correction : UnbackedClaimGuardrail.RENDER_CORRECTIONS) {
            if (renderHolder.get() != null || !UnbackedClaimGuardrail.claimsVisual(content)) {
                break;
            }
            log.info("Resposta anuncia visualização sem chamar render; refazendo com correção. "
                    + "conversationId={}", conversationId);
            content = ask(correction, conversationId);
        }

        for (String correction : UnbackedClaimGuardrail.ACTION_CORRECTIONS) {
            if (pendingActionHolder.get() != null || !UnbackedClaimGuardrail.claimsAction(content)) {
                break;
            }
            log.info("Resposta anuncia ação pendente sem chamar tool de escrita; refazendo com correção. "
                    + "conversationId={}", conversationId);
            content = ask(correction, conversationId);
        }

        for (String correction : UnbackedClaimGuardrail.DATA_CORRECTIONS) {
            if (!answeredWithoutData(content)) {
                break;
            }
            log.info("Resposta traz dados sem nenhuma tool chamada; refazendo com correção. conversationId={}",
                    conversationId);
            toolCallHolder.reset();
            content = ask(correction, conversationId);
        }

        tracingGateway.tag("langfuse.trace.output", content);

        RenderableContent renderData = renderHolder.get();
        PendingAction pending = pendingActionHolder.get();
        String text = AnswerNotices.withPendingActionNotice(AnswerNotices.withoutDuplicatedTable(content, renderData), pending);

        if (claimsUnregisteredAction(content, pending)) {
            text = AnswerNotices.withUnregisteredActionNotice(text);
        } else if (writeWentNowhere(writeRequested, pending, content)) {
            log.warn("Pedido de escrita sem pendência registrada; avisando que nada foi gravado. "
                    + "conversationId={}", conversationId);
            text = AnswerNotices.withNothingWrittenNotice(text);
        } else if (assertsUnverifiedData(content)) {
            log.warn("Resposta sem nenhuma tool chamada depois das correções; desmentindo na tela. "
                    + "conversationId={}", conversationId);
            text = AnswerNotices.withUnverifiedAnswerNotice(text);
        }
        return new ChatMessage("assistant", text, renderData, pending);
    }

    private boolean answeredWithoutData(String content) {
        return toolCallHolder.isEmpty()
                && renderHolder.get() == null
                && UnbackedClaimGuardrail.claimsData(content);
    }

    private boolean writeRequested(String userMessage, String conversationId) {
        String message = nullToEmpty(userMessage).strip();
        if (UnbackedClaimGuardrail.isWriteRequest(message)) {
            conversationStateGateway.setWriteIntent(conversationId, true);
            return true;
        }
        if (conversationStateGateway.hasWriteIntent(conversationId) && UnbackedClaimGuardrail.isAffirmative(message)) {
            return true;
        }
        conversationStateGateway.setWriteIntent(conversationId, false);
        return false;
    }

    private boolean writeWentNowhere(boolean writeRequested, PendingAction pending, String content) {
        String text = nullToEmpty(content).strip();
        return writeRequested && pending == null
                && !text.endsWith("?")
                && !UnbackedClaimGuardrail.isDenial(text);
    }

    private String ask(String userMessage, String conversationId) {
        return chatModelGateway.ask(userMessage, conversationId);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean claimsUnregisteredAction(String content, PendingAction pending) {
        return pending == null && UnbackedClaimGuardrail.claimsAction(content);
    }

    private boolean assertsUnverifiedData(String content) {
        return answeredWithoutData(content) && !nullToEmpty(content).strip().endsWith("?");
    }

    private void tagRequest(String userMessage, String conversationId) {
        tracingGateway.tag("langfuse.trace.name", "chat");
        tracingGateway.tag("langfuse.session.id", conversationId);
        tracingGateway.tag("session.id", conversationId);
        tracingGateway.tag("langfuse.trace.input", userMessage);
    }
}
