package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatClientConfigExtraBodyTest {

    @Test
    void parsesQwenThinkingToggle() {
        Map<String, Object> extraBody = ChatClientConfig
                .parseExtraBody("{\"chat_template_kwargs\": {\"enable_thinking\": false}}");

        assertThat(extraBody).containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
    }

    @Test
    void parsesDeepSeekThinkingToggle() {
        Map<String, Object> extraBody = ChatClientConfig
                .parseExtraBody("{\"thinking\": {\"type\": \"disabled\"}}");

        assertThat(extraBody).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void defaultsToEmptyMapWhenVariableIsAbsent() {
        assertThat(ChatClientConfig.parseExtraBody("")).isEmpty();
        assertThat(ChatClientConfig.parseExtraBody(null)).isEmpty();
        assertThat(ChatClientConfig.parseExtraBody("{}")).isEmpty();
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> ChatClientConfig.parseExtraBody("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_EXTRA_BODY");
    }
}
