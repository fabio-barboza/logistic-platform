package br.com.fabio.logisticagent.confirm;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "pending_action")
public class PendingAction {

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

    protected PendingAction() {
    }

    public PendingAction(String id, String sessionId, String toolName, String argsJson,
            Instant createdAt, Map<String, String> details) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.createdAt = createdAt;

        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String toolName() {
        return toolName;
    }

    public String argsJson() {
        return argsJson;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, String> details() {
        return details;
    }

    public boolean matches(String otherTool, String otherArgs) {
        return toolName.equals(otherTool) && argsJson.equals(otherArgs == null ? "" : otherArgs);
    }
}
