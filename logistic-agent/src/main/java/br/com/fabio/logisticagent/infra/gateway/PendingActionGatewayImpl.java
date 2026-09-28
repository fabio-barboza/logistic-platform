package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import br.com.fabio.logisticagent.infra.mapper.PendingActionEntityMapper;
import br.com.fabio.logisticagent.infra.repository.PendingActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class PendingActionGatewayImpl implements PendingActionGateway {

    private static final Logger log = LoggerFactory.getLogger(PendingActionGatewayImpl.class);

    private final PendingActionRepository repository;

    public PendingActionGatewayImpl(PendingActionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public PendingAction register(String sessionId, String toolName, String argsJson, Map<String, String> details) {
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), sessionId, toolName,
                argsJson == null ? "" : argsJson, Instant.now(), details);
        repository.save(PendingActionEntityMapper.toEntity(action));
        log.info("Ação pendente registrada: id={} tool={} sessionId={}", action.id(), toolName, sessionId);
        return action;
    }

    @Override
    @Transactional
    public PendingAction take(String id, String sessionId) {
        PendingAction action = repository
                .findByIdAndSessionIdAndCreatedAtAfter(id, sessionId, Instant.now().minus(TTL))
                .map(PendingActionEntityMapper::toDomain)
                .orElse(null);
        if (action == null || repository.deleteConsuming(id, sessionId) == 0) {
            log.info("Confirmação sem pendência correspondente: id={} sessionId={}", id, sessionId);
            return null;
        }
        return action;
    }

    @Override
    public int size() {
        return (int) repository.count();
    }

    @Override
    @Transactional
    public int deleteCreatedBefore(Instant limit) {
        return repository.deleteExpired(limit);
    }
}
