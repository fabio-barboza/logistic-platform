-- ============================================================
-- Estado de conversa do logistic-agent (banco agentdb, exclusivo deste serviço)
-- ============================================================

-- Pendência de escrita aguardando confirmação do usuário (fluxo human-in-the-loop).
--
-- session_id: apesar do nome, guarda o conversationId (AuthenticatedUser.conversationId, que
-- combina o "sub" do JWT com o sessionId enviado pelo webui), acompanhando o nome do campo em
-- PendingAction.
CREATE TABLE pending_action (
    id           VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(255) NOT NULL,
    tool_name    VARCHAR(128) NOT NULL,
    -- details_json é texto, nunca jsonb: o jsonb não preserva ordem de chave, e o card de
    -- exclusão mostra os campos na ordem que DeletionTargetLookup declarou.
    args_json    TEXT         NOT NULL,
    details_json TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL,

    CONSTRAINT pk_pending_action PRIMARY KEY (id)
);

CREATE INDEX idx_pending_action_created_at ON pending_action (created_at);

-- Pedido de escrita aguardando o aceite curto do usuário ("sim, pode cadastrar"), por conversa.
CREATE TABLE conversation_state (
    conversation_id VARCHAR(255) NOT NULL,
    write_intent    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMP    NOT NULL,

    CONSTRAINT pk_conversation_state PRIMARY KEY (conversation_id)
);

CREATE INDEX idx_conversation_state_updated_at ON conversation_state (updated_at);

-- Tabela da ChatMemory do Spring AI, cópia do schema-postgresql.sql de
-- spring-ai-model-chat-memory-repository-jdbc 2.0.0 com conversation_id alargado: o DDL de lá usa
-- varchar(36) (assume UUID) e a chave daqui é sub|sessionId, ~70 caracteres.
--
-- Não é um ALTER porque o Flyway roda antes do inicializador de schema do Spring AI — a tabela
-- ainda não existe neste ponto. É a mesma ordem que faz isto funcionar: o CREATE TABLE IF NOT
-- EXISTS dele encontra a tabela pronta. ChatMemorySchemaTest cobre o drift.
CREATE TABLE spring_ai_chat_memory (
    conversation_id VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(10)  NOT NULL
        CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP    NOT NULL,
    sequence_id     BIGINT       NOT NULL
);

CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");

CREATE INDEX spring_ai_chat_memory_conversation_id_sequence_id_idx
    ON spring_ai_chat_memory (conversation_id, sequence_id);
