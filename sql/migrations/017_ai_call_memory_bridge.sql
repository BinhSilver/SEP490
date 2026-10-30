-- Apply 010_kaiwa_memory.sql first. Attach its memory to the existing AI Call sessions.
CREATE TABLE IF NOT EXISTS kaiwa_conversation_memory (
    conversation_session_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    memory_session_id BIGINT UNSIGNED NOT NULL,
    last_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    engine_state_json JSON NULL,
    UNIQUE KEY uq_kaiwa_conversation_memory_session (memory_session_id),
    KEY idx_kaiwa_conversation_memory_user (user_id),
    CONSTRAINT fk_kaiwa_bridge_conversation FOREIGN KEY (conversation_session_id) REFERENCES conversation_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_kaiwa_bridge_memory FOREIGN KEY (memory_session_id) REFERENCES kaiwa_free_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_kaiwa_bridge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
