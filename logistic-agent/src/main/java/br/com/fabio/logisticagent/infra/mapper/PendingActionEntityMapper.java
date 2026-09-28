package br.com.fabio.logisticagent.infra.mapper;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.infra.entity.PendingActionEntity;

public final class PendingActionEntityMapper {

    private PendingActionEntityMapper() {
    }

    public static PendingActionEntity toEntity(PendingAction action) {
        return new PendingActionEntity(action.id(), action.sessionId(), action.toolName(), action.argsJson(),
                action.createdAt(), action.details());
    }

    public static PendingAction toDomain(PendingActionEntity entity) {
        return new PendingAction(entity.getId(), entity.getSessionId(), entity.getToolName(),
                entity.getArgsJson(), entity.getCreatedAt(), entity.getDetails());
    }
}
