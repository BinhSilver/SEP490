-- Flashcard Speed Recall. MySQL 8+. Apply once before deploying the new entities.
-- Additive: no existing study, quiz, progress, or card data is deleted.
-- DDL auto-commits in MySQL; inspect schema before retrying a partially applied migration.
ALTER TABLE flashcard_study_session
    ADD COLUMN creation_request_id VARCHAR(36) NULL,
    ADD COLUMN creation_fingerprint VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_flashcard_session_creation (user_id, creation_request_id),
    ADD KEY idx_flashcard_session_history (user_id, set_id, session_type, started_at),
    DROP CHECK chk_flashcard_session_type,
    ADD CONSTRAINT chk_flashcard_session_type CHECK (session_type IN ('FLASHCARD','QUIZ','SPEED_RECALL'));

CREATE TABLE flashcard_speed_recall_session (
    session_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(24) NOT NULL,
    paused_from VARCHAR(24) NULL,
    current_question_index INT NOT NULL DEFAULT 0,
    total_questions INT NOT NULL,
    correct_count INT NOT NULL DEFAULT 0,
    wrong_count INT NOT NULL DEFAULT 0,
    timeout_count INT NOT NULL DEFAULT 0,
    score INT NOT NULL DEFAULT 0,
    streak INT NOT NULL DEFAULT 0,
    best_streak INT NOT NULL DEFAULT 0,
    total_response_ms BIGINT NOT NULL DEFAULT 0,
    rules_version VARCHAR(32) NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    selection_filter VARCHAR(16) NOT NULL,
    auto_submit BOOLEAN NOT NULL DEFAULT FALSE,
    retry_of_session_id BIGINT UNSIGNED NULL,
    feedback_until DATETIME(6) NULL,
    paused_remaining_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id),
    KEY idx_sr_expiry (state, expires_at),
    CONSTRAINT fk_sr_study FOREIGN KEY (session_id) REFERENCES flashcard_study_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_retry FOREIGN KEY (retry_of_session_id) REFERENCES flashcard_speed_recall_session(session_id) ON DELETE SET NULL,
    CONSTRAINT chk_sr_total CHECK (total_questions >= 1),
    CONSTRAINT chk_sr_counts CHECK (correct_count >= 0 AND wrong_count >= 0 AND timeout_count >= 0
        AND timeout_count <= wrong_count AND correct_count + wrong_count <= total_questions)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flashcard_speed_recall_answer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id BIGINT UNSIGNED NOT NULL,
    flashcard_card_id BIGINT UNSIGNED NULL,
    question_index INT NOT NULL,
    question_text VARCHAR(500) NOT NULL,
    expected_answer VARCHAR(500) NOT NULL,
    reading VARCHAR(255) NOT NULL,
    meaning TEXT NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    user_answer VARCHAR(500) NULL,
    normalized_answer VARCHAR(500) NULL,
    response_ms INT NULL,
    score_awarded INT NOT NULL DEFAULT 0,
    time_limit_ms INT NULL,
    streak_before INT NULL,
    issued_at DATETIME(6) NULL,
    deadline_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    answer_request_id VARCHAR(36) NULL,
    request_fingerprint VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sr_question_position (session_id, question_index),
    KEY idx_sr_review (session_id, outcome),
    CONSTRAINT fk_sr_answer_session FOREIGN KEY (session_id) REFERENCES flashcard_speed_recall_session(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_answer_card FOREIGN KEY (flashcard_card_id) REFERENCES flashcard_card(id) ON DELETE SET NULL,
    CONSTRAINT chk_sr_answer_outcome CHECK (outcome IN ('PENDING','ACTIVE','CORRECT','WRONG','TIMEOUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
