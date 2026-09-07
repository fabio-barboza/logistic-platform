package br.com.fabio.logisticagent.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Linha de {@code conversation_state}: o pedido de escrita ainda de pé numa conversa. */
@Entity
@Table(name = "conversation_state")
class ConversationStateEntity {

    @Id
    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "write_intent", nullable = false)
    private boolean writeIntent;

    /** Quem decide, na purga, se a conversa foi abandonada. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationStateEntity() {
    }

    ConversationStateEntity(String conversationId) {
        this.conversationId = conversationId;
        this.updatedAt = Instant.now();
    }

    boolean isWriteIntent() {
        return writeIntent;
    }

    void setWriteIntent(boolean writeIntent) {
        this.writeIntent = writeIntent;
        this.updatedAt = Instant.now();
    }
}
