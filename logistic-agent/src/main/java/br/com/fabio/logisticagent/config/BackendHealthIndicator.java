package br.com.fabio.logisticagent.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

/**
 * Verifica periodicamente se o logistic-api está acessível através do actuator health.
 * O MCP client conecta no startup, mas se a API cair depois o agent continua rodando
 * sem detectar — este indicador expõe o status real no /actuator/health.
 */
@Component
@ConfigurationProperties(prefix = "logistic.backend")
public class BackendHealthIndicator implements HealthIndicator {

    private String url = "http://localhost:8081";

    private RestClient restClient;

    private volatile boolean online = false;
    private volatile String detail = "";

    public BackendHealthIndicator() {
        this.restClient = RestClient.builder()
                .baseUrl(url + "/actuator")
                .build();
    }

    @Scheduled(fixedDelayString = "${logistic.backend.check-interval:15000}", initialDelayString = "${logistic.backend.initial-delay:5000}")
    void check() {
        try {
            Map<String, Object> health = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);

            if (health != null && "UP".equals(health.get("status"))) {
                online = true;
                detail = "";
            } else {
                setOffline("API respondeu mas status não é UP");
            }
        } catch (ResourceAccessException e) {
            setOffline("Não foi possível conectar ao logistic-api em " + url);
        } catch (Exception e) {
            setOffline("Erro ao verificar logistic-api: " + e.getMessage());
        }
    }

    private void setOffline(String reason) {
        online = false;
        detail = reason;
    }

    @Override
    public Health health() {
        if (online) {
            return Health.up().build();
        } else {
            return Health.down().withDetail("detail", detail).build();
        }
    }
}
