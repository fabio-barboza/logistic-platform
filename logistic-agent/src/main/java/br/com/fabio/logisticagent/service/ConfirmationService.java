package br.com.fabio.logisticagent.service;

import br.com.fabio.logisticagent.confirm.PendingAction;
import br.com.fabio.logisticagent.confirm.IPendingActionStore;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ConfirmRequestDTO;
import br.com.fabio.logisticagent.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;

/**
 * Executa (ou descarta) a ação de escrita que o usuário confirmou na tela.
 *
 * <p>Resolve o {@link org.springframework.ai.tool.ToolCallback} pelo <b>nome</b> da tool e chama
 * com o JSON registrado, <b>sem passar pela LLM</b>: o que roda tem que ser byte a byte o que o
 * usuário viu na tela.
 *
 * <p>O desfecho entra na ChatMemory da sessão porque o modelo não participa deste passo e, sem
 * isso, o turno seguinte ("qual o id dele?") responderia sobre uma ação que, para ele, ficou
 * pendente para sempre.
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    private final IPendingActionStore store;
    private final ChatMemory chatMemory;
    private final JsonMapper jsonMapper;

    /**
     * O provider CRU, <b>nunca</b> o {@code ConfirmingToolCallbackProvider}: pelo decorado, a
     * confirmação registraria outra pendência em vez de gravar.
     */
    private final ToolCallbackProvider mcpToolCallbacks;

    public ConfirmationService(IPendingActionStore store, ChatMemory chatMemory, JsonMapper jsonMapper,
            ToolCallbackProvider mcpToolCallbacks) {
        this.store = store;
        this.chatMemory = chatMemory;
        this.jsonMapper = jsonMapper;
        this.mcpToolCallbacks = mcpToolCallbacks;
    }

    public ChatMessageDTO resolve(ConfirmRequestDTO request) {
        // Mesma chave que o ChatService usou ao registrar a pendência (ver AuthenticatedUser):
        // sub + sessionId, não o sessionId cru — senão o id de uma pendência seria resgatável por
        // qualquer um que soubesse (ou forçasse) o mesmo sessionId de outro usuário.
        String conversationId = AuthenticatedUser.conversationId(request.sessionId());
        PendingAction action = store.take(request.actionId(), conversationId);
        if (action == null) {
            // Expirou, já foi confirmada, ou a página foi recarregada (sessionId novo a cada load).
            return message("Não encontrei essa ação pendente — ela pode já ter sido confirmada ou "
                    + "ter expirado. Peça de novo no chat, por favor.");
        }
        if (!request.approved()) {
            log.info("Ação {} ({}) cancelada pelo usuário", action.id(), action.toolName());
            remember(conversationId, "O usuário CANCELOU a ação " + action.toolName()
                    + ". Nada foi gravado. Não a execute nem a mencione como concluída.");
            return message("Ação cancelada. Nada foi gravado.");
        }
        // Null aqui não é "ação não encontrada" — o take() acima já consumiu a pendência. É a
        // tool não estar no handshake MCP desta instância, sintoma de API fora do ar no startup.
        ToolCallback callback = resolveCallback(action.toolName());
        if (callback == null) {
            log.warn("Tool {} não encontrada nas tools MCP ao confirmar a ação {}",
                    action.toolName(), action.id());
            remember(conversationId, "A confirmação da ação " + action.toolName()
                    + " não pôde ser executada porque o backend está indisponível. Nada foi gravado.");
            return message("Não foi possível executar a ação: o backend está indisponível no "
                    + "momento. Aguarde e tente novamente em instantes.");
        }
        try {
            String result = callback.call(action.argsJson());
            log.info("Ação {} ({}) confirmada e executada", action.id(), action.toolName());
            remember(conversationId, "O usuário CONFIRMOU a ação " + action.toolName()
                    + " e ela foi executada agora. Retorno da tool: " + result);
            return message("✅ Ação confirmada e executada.\n\n```json\n" + readable(result) + "\n```");
        } catch (RuntimeException e) {
            // Erro de negócio da API (e-mail duplicado, id inexistente) ou API fora do ar. A
            // pendência já foi consumida: repetir exige um pedido novo no chat, e não outro clique.
            log.warn("Falha ao executar a ação {} ({})", action.id(), action.toolName(), e);
            remember(conversationId, "A ação " + action.toolName() + " foi confirmada mas FALHOU: "
                    + e.getMessage() + ". Nada foi gravado.");
            return message("Não foi possível executar a ação: " + e.getMessage());
        }
    }

    /**
     * Desembrulha o retorno do MCP para o que o usuário lê no chat.
     *
     * <p>A tool devolve o conteúdo MCP cru — {@code [{"text":"{\"id\":...}"}]} —, com o registro
     * criado dentro de uma string escapada. Jogar isso na tela mostra mais barra invertida do que
     * dado. O texto de dentro é extraído e reindentado; qualquer formato inesperado cai no cru, que
     * é feio mas nunca esconde o que aconteceu.
     */
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

    /** O nome vem do {@code getToolDefinition().name()} do próprio callback: igualdade direta. */
    private ToolCallback resolveCallback(String toolName) {
        return Arrays.stream(mcpToolCallbacks.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElse(null);
    }

    private void remember(String conversationId, String text) {
        chatMemory.add(conversationId, new AssistantMessage(text));
    }

    private ChatMessageDTO message(String content) {
        return new ChatMessageDTO("assistant", content, null);
    }
}
