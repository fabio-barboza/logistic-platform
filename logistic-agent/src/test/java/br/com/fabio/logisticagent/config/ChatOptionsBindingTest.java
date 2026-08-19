package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que o application.yml realmente desliga o reasoning do modelo. O extra-body vai
 * num Map<String, Object> com chaves entre colchetes ("[chat_template_kwargs]") justamente
 * para o binder do Spring não normalizar o underscore — se alguém tirar os colchetes, o
 * llama-server recebe uma chave desconhecida, ignora, e o thinking volta a queimar tokens
 * sem nenhum erro visível. Este teste é o alarme para isso.
 */
class ChatOptionsBindingTest {

    @Test
    void applicationYmlDisablesModelThinking() throws IOException {
        OpenAiChatOptions options = bindChatOptions();

        assertThat(options.getExtraBody())
                .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
    }

    @Test
    void applicationYmlKeepsModelAndSamplingOptions() throws IOException {
        OpenAiChatOptions options = bindChatOptions();

        assertThat(options.getModel()).isEqualTo("qwen3.6:35B");
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).isEqualTo(16000);
    }

    private OpenAiChatOptions bindChatOptions() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));

        OpenAiChatProperties properties = Binder.get(environment)
                .bind(OpenAiChatProperties.CONFIG_PREFIX, OpenAiChatProperties.class)
                .orElseThrow(() -> new AssertionError("Nenhuma propriedade sob " + OpenAiChatProperties.CONFIG_PREFIX));

        return properties.toOptions();
    }
}
