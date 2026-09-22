package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOptionsBindingTest {

    @Test
    void applicationYmlKeepsSamplingOptions() throws IOException {
        OpenAiChatOptions options = bindChatOptions();

        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).isEqualTo(16000);
    }

    private OpenAiChatOptions bindChatOptions() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();

        environment.getPropertySources().addFirst(new MapPropertySource("test-llm-vars", Map.of(
                "LLM_MODEL", "test-model",
                "LLM_BASE_URL", "http://test.invalid",
                "LLM_API_KEY", "test-key")));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));

        OpenAiChatProperties properties = Binder.get(environment)
                .bind(OpenAiChatProperties.CONFIG_PREFIX, OpenAiChatProperties.class)
                .orElseThrow(() -> new AssertionError("Nenhuma propriedade sob " + OpenAiChatProperties.CONFIG_PREFIX));

        return properties.toOptions();
    }
}
