package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.BackendHealthGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component("backendHealthIndicator")
public class BackendHealthGatewayImpl implements BackendHealthGateway, HealthIndicator {

    private final String url;

    private final RestClient restClient;

    private volatile boolean online = false;
    private volatile String detail = "";

    public BackendHealthGatewayImpl(@Value("${logistic.backend.url}") String url) {
        this.url = url;
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
    public boolean isOnline() {
        return online;
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
