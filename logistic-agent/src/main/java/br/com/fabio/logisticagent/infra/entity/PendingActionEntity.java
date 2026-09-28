package br.com.fabio.logisticagent.infra.entity;

import br.com.fabio.logisticagent.infra.support.DetailsJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "pending_action")
public class PendingActionEntity {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "args_json", nullable = false)
    private String argsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "details_json", nullable = false)
    @Convert(converter = DetailsJsonConverter.class)
    private Map<String, String> details;

    protected PendingActionEntity() {
    }

    public PendingActionEntity(String id, String sessionId, String toolName, String argsJson,
            Instant createdAt, Map<String, String> details) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.createdAt = createdAt;
        this.details = details;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
