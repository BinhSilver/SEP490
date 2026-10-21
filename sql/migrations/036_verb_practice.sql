-- [JLAS-VERB-PRACTICE] Run once in the existing JLAS database before restarting.
-- Adds practice history and per-verb/form progress. No existing data is changed.
CREATE TABLE IF NOT EXISTS verb_practice_session (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    mode VARCHAR(20) NOT NULL,
    payload LONGTEXT NOT NULL,
    answered_count INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    INDEX idx_verb_session_user_started (user_id, started_at),
    CONSTRAINT fk_verb_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS verb_practice_progress (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    verb_id VARCHAR(40) NOT NULL,
    form_code VARCHAR(30) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_verb_progress (user_id, verb_id, form_code),
    CONSTRAINT fk_verb_progress_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
