package br.com.fabio.logisticagent.entrypoint.mapper;

import br.com.fabio.logisticagent.core.agent.ActionLabels;
import br.com.fabio.logisticagent.core.domain.chat.ChatMessage;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.entrypoint.response.ChatResponse;
import br.com.fabio.logisticagent.entrypoint.response.PendingActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(ResponseMapper.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ResponseMapper() {
    }

    public static ChatResponse toResponse(ChatMessage message) {
        PendingAction pending = message.pendingAction();
        return new ChatResponse(message.role(), message.content(), message.renderData(),
                pending == null ? null : toResponse(pending));
    }

    public static PendingActionResponse toResponse(PendingAction action) {
        return new PendingActionResponse(action.id(), action.toolName(), ActionLabels.summary(action.toolName()),
                displayed(action), ActionLabels.destructive(action.toolName()));
    }

    private static Map<String, String> displayed(PendingAction action) {
        if (action.details().isEmpty()) {
            return arguments(action.argsJson());
        }
        Map<String, String> displayed = new LinkedHashMap<>(action.details());
        displayed.putAll(arguments(action.argsJson()));
        return displayed;
    }

    private static Map<String, String> arguments(String argsJson) {
        Map<String, String> arguments = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = MAPPER.readValue(argsJson, new TypeReference<Map<String, Object>>() {
            });
            parsed.forEach((key, value) -> arguments.put(ActionLabels.label(key), String.valueOf(value)));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Argumentos da ação {} não são um JSON de objeto: {}", argsJson, e.getMessage());
            arguments.put("Argumentos", argsJson);
        }
        return arguments;
    }
}
