package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.core.settings.StatePurgeSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class StatePurgeConfig {

    @Bean
    StatePurgeSettings statePurgeSettings(
            @Value("${logistic.agent.state-purge.chat-memory-ttl:24h}") Duration chatMemoryTtl) {
        return new StatePurgeSettings(chatMemoryTtl);
    }
}
