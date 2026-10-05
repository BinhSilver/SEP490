-- MySQL 8+. Independent free-chat sessions; scenario sessions are unchanged.
CREATE TABLE IF NOT EXISTS kaiwa_free_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    summary TEXT NOT NULL,
    recent_turns JSON NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    processed_revision BIGINT NOT NULL DEFAULT 0,
    chat_token VARCHAR(36) NULL,
    chat_started_at DATETIME(6) NULL,
    chat_lease_until DATETIME(6) NULL,
    memory_token VARCHAR(36) NULL,
    memory_lease_until DATETIME(6) NULL,
    retry_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at DATETIME(6) NULL,
    KEY idx_kaiwa_free_user (user_id, updated_at),
    KEY idx_kaiwa_free_pending (retry_at, memory_lease_until, updated_at),
    CONSTRAINT fk_kaiwa_free_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kaiwa_memory (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    memory_key VARCHAR(120) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    topic VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    importance TINYINT NOT NULL DEFAULT 3,
    source_session_id BIGINT UNSIGNED NOT NULL,
    evidence_turn_id BIGINT NULL,
    evidence TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    observed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    embedding JSON NULL,
    embedding_model VARCHAR(120) NULL,
    embedding_retry_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_kaiwa_memory_key (user_id, memory_key),
    KEY idx_kaiwa_memory_user_active (user_id, status, kind, expires_at),
    KEY idx_kaiwa_memory_source (source_session_id),
    CONSTRAINT fk_kaiwa_memory_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_kaiwa_memory_session FOREIGN KEY (source_session_id) REFERENCES kaiwa_free_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

