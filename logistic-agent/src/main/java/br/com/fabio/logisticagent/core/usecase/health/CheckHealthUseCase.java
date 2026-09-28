package br.com.fabio.logisticagent.core.usecase.health;

import br.com.fabio.logisticagent.core.gateway.BackendHealthGateway;
import org.springframework.stereotype.Service;

@Service
public class CheckHealthUseCase {

    private final BackendHealthGateway backendHealth;

    public CheckHealthUseCase(BackendHealthGateway backendHealth) {
        this.backendHealth = backendHealth;
    }

    public boolean execute() {
        return backendHealth.isOnline();
    }
}
