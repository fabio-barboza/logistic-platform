package br.com.fabio.logisticagent.infra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "conversation_state")
public class ConversationStateEntity {

    @Id
    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "write_intent", nullable = false)
    private boolean writeIntent;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationStateEntity() {
    }

    public ConversationStateEntity(String conversationId) {
        this.conversationId = conversationId;
        this.updatedAt = Instant.now();
    }

    public boolean isWriteIntent() {
        return writeIntent;
    }

    public void setWriteIntent(boolean writeIntent) {
        this.writeIntent = writeIntent;
        this.updatedAt = Instant.now();
    }
}
