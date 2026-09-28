package br.com.fabio.logisticagent.entrypoint.scheduler;

import br.com.fabio.logisticagent.core.usecase.maintenance.PurgeAgentStateUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentStatePurgeScheduler {

    private final PurgeAgentStateUseCase purgeAgentStateUseCase;

    public AgentStatePurgeScheduler(PurgeAgentStateUseCase purgeAgentStateUseCase) {
        this.purgeAgentStateUseCase = purgeAgentStateUseCase;
    }

    @Scheduled(fixedDelayString = "${logistic.agent.state-purge.interval:3600000}",
            initialDelayString = "${logistic.agent.state-purge.interval:3600000}")
    public void purge() {
        purgeAgentStateUseCase.execute();
    }
}
