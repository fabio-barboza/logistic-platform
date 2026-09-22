package br.com.fabio.logisticagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemorySchemaTest {

    private static final String EXPECTED_UPSTREAM_SCHEMA = """
            CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
                conversation_id VARCHAR(36) NOT NULL,
                content TEXT NOT NULL,
                type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
                "timestamp" TIMESTAMP NOT NULL,
                sequence_id BIGINT NOT NULL
                );

            CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
            ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

            CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
            ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);
            """;

    @Test
    void upstreamSchemaHasNotChanged() throws IOException {
        String upstream = read("org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql");

        assertThat(normalized(upstream)).isEqualTo(normalized(EXPECTED_UPSTREAM_SCHEMA));
    }

    @Test
    void migrationCopiesUpstreamSchemaWithOnlyTheWiderConversationId() throws IOException {
        String migration = normalized(read("db/migration/V1__agent_state.sql"));

        assertThat(migration).contains("conversation_id VARCHAR(255) NOT NULL");
        assertThat(migration).doesNotContain("conversation_id VARCHAR(36)");
        assertThat(migration).contains("content TEXT NOT NULL");
        assertThat(migration).contains("type VARCHAR(10) NOT NULL");
        assertThat(migration).contains("CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))");
        assertThat(migration).contains("\"timestamp\" TIMESTAMP NOT NULL");
        assertThat(migration).contains("sequence_id BIGINT NOT NULL");
        assertThat(migration).contains("spring_ai_chat_memory_conversation_id_timestamp_idx");
        assertThat(migration).contains("spring_ai_chat_memory_conversation_id_sequence_id_idx");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
