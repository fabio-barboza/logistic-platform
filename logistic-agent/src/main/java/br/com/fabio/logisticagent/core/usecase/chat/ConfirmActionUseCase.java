package br.com.fabio.logisticagent.core.usecase.chat;

import br.com.fabio.logisticagent.core.domain.chat.ChatMessage;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.gateway.ChatHistoryGateway;
import br.com.fabio.logisticagent.core.gateway.McpToolGateway;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

@Service
public class ConfirmActionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmActionUseCase.class);

    private final PendingActionGateway pendingActionGateway;
    private final ChatHistoryGateway chatHistoryGateway;
    private final McpToolGateway mcpToolGateway;
    private final JsonMapper jsonMapper;

    public ConfirmActionUseCase(PendingActionGateway pendingActionGateway, ChatHistoryGateway chatHistoryGateway,
            McpToolGateway mcpToolGateway, JsonMapper jsonMapper) {
        this.pendingActionGateway = pendingActionGateway;
        this.chatHistoryGateway = chatHistoryGateway;
        this.mcpToolGateway = mcpToolGateway;
        this.jsonMapper = jsonMapper;
    }

    public ChatMessage execute(String conversationId, String actionId, boolean approved) {
        PendingAction action = pendingActionGateway.take(actionId, conversationId);
        if (action == null) {

            return message("Não encontrei essa ação pendente — ela pode já ter sido confirmada ou "
                    + "ter expirado. Peça de novo no chat, por favor.");
        }
        if (!approved) {
            log.info("Ação {} ({}) cancelada pelo usuário", action.id(), action.toolName());
            remember(conversationId, "O usuário CANCELOU a ação " + action.toolName()
                    + ". Nada foi gravado. Não a execute nem a mencione como concluída.");
            return message("Ação cancelada. Nada foi gravado.");
        }

        Optional<String> result;
        try {
            result = mcpToolGateway.call(action.toolName(), action.argsJson());
        } catch (RuntimeException e) {

            log.warn("Falha ao executar a ação {} ({})", action.id(), action.toolName(), e);
            remember(conversationId, "A ação " + action.toolName() + " foi confirmada mas FALHOU: "
                    + e.getMessage() + ". Nada foi gravado.");
            return message("Não foi possível executar a ação: " + e.getMessage());
        }
        if (result.isEmpty()) {
            log.warn("Tool {} não encontrada nas tools MCP ao confirmar a ação {}",
                    action.toolName(), action.id());
            remember(conversationId, "A confirmação da ação " + action.toolName()
                    + " não pôde ser executada porque o backend está indisponível. Nada foi gravado.");
            return message("Não foi possível executar a ação: o backend está indisponível no "
                    + "momento. Aguarde e tente novamente em instantes.");
        }
        log.info("Ação {} ({}) confirmada e executada", action.id(), action.toolName());
        remember(conversationId, "O usuário CONFIRMOU a ação " + action.toolName()
                + " e ela foi executada agora. Retorno da tool: " + result.get());
        return message("✅ Ação confirmada e executada.\n\n```json\n" + readable(result.get()) + "\n```");
    }

    private String readable(String result) {
        try {
            JsonNode node = jsonMapper.readTree(result);
            if (node.isArray() && !node.isEmpty() && node.get(0).path("text").isString()) {
                StringBuilder texts = new StringBuilder();
                for (JsonNode item : node) {
                    texts.append(texts.isEmpty() ? "" : "\n").append(item.path("text").asString());
                }
                node = jsonMapper.readTree(texts.toString());
            }
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JacksonException e) {
            return result;
        }
    }

    private void remember(String conversationId, String text) {
        chatHistoryGateway.appendAssistant(conversationId, text);
    }

    private ChatMessage message(String content) {
        return new ChatMessage("assistant", content, null);
    }
}
