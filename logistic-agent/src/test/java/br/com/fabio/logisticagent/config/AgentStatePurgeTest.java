package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercita {@link AgentStatePurge} contra o schema real da migration, incluindo
 * {@code spring_ai_chat_memory} — o corte dela é pela conversa inteira, não mensagem a mensagem.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(AgentStatePurge.class)
class AgentStatePurgeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentStatePurge purge;

    @Test
    void deletesRowsPastTheTtlAndKeepsRecentOnes() {
        Instant now = Instant.now();

        // ChatMemory: o corte é pela conversa inteira, não mensagem a mensagem — "old-conv" some
        // por inteiro (a mensagem de 30h incluída) porque a ÚLTIMA mensagem dela já passou de
        // 24h; "recent-conv" fica intacta porque a última mensagem é recente, mesmo tendo uma
        // mensagem tão antiga quanto a que foi apagada da outra conversa.
        insertChatMessage("old-conv", 1, Timestamp.from(now.minus(25, ChronoUnit.HOURS)));
        insertChatMessage("recent-conv", 1, Timestamp.from(now.minus(30, ChronoUnit.HOURS)));
        insertChatMessage("recent-conv", 2, Timestamp.from(now.minus(1, ChronoUnit.HOURS)));

        insertPendingAction("old-action", Timestamp.from(now.minus(20, ChronoUnit.MINUTES)));
        insertPendingAction("recent-action", Timestamp.from(now.minus(5, ChronoUnit.MINUTES)));

        insertConversationState("old-state", Timestamp.from(now.minus(25, ChronoUnit.HOURS)));
        insertConversationState("recent-state", Timestamp.from(now.minus(1, ChronoUnit.HOURS)));

        purge.purge();

        assertThat(countChatMessages("old-conv")).isZero();
        assertThat(countChatMessages("recent-conv")).isEqualTo(2);
        assertThat(countRows("pending_action", "id", "old-action")).isZero();
        assertThat(countRows("pending_action", "id", "recent-action")).isEqualTo(1);
        assertThat(countRows("conversation_state", "conversation_id", "old-state")).isZero();
        assertThat(countRows("conversation_state", "conversation_id", "recent-state")).isEqualTo(1);
    }

    private void insertChatMessage(String conversationId, long sequenceId, Timestamp timestamp) {
        jdbcTemplate.update("""
                INSERT INTO spring_ai_chat_memory (conversation_id, content, type, "timestamp", sequence_id)
                VALUES (?, 'oi', 'USER', ?, ?)
                """, conversationId, timestamp, sequenceId);
    }

    private void insertPendingAction(String id, Timestamp createdAt) {
        jdbcTemplate.update("""
                INSERT INTO pending_action (id, session_id, tool_name, args_json, details_json, created_at)
                VALUES (?, 'session-1', 'deleteDriver', '{}', '{}', ?)
                """, id, createdAt);
    }

    private void insertConversationState(String conversationId, Timestamp updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO conversation_state (conversation_id, write_intent, updated_at)
                VALUES (?, false, ?)
                """, conversationId, updatedAt);
    }

    private int countChatMessages(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spring_ai_chat_memory WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private int countRows(String table, String idColumn, String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }
}
