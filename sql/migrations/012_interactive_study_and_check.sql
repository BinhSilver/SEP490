-- [JLAS-W0193] Vocabulary study sessions + micro-check state for Interactive Learning Path V2.

-- Study session: one per activity; holds the membership revision being studied so a reload
-- resumes the same session and a replan (new revision) resets cleanly.
CREATE TABLE learning_study_session (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id BIGINT UNSIGNED NOT NULL,
    source_revision  VARCHAR(64) NOT NULL,
    row_version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_study_session_activity (activity_item_id),
    CONSTRAINT fk_learning_study_session_item FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Per-membership flip/remember state. "Remembered" is study evidence, never mastery.
CREATE TABLE learning_study_card (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    study_session_id BIGINT UNSIGNED NOT NULL,
    canonical_id     BIGINT UNSIGNED NOT NULL,
    order_index      INT UNSIGNED NOT NULL,
    flipped          TINYINT(1) NOT NULL DEFAULT 0,
    remembered       TINYINT(1) NOT NULL DEFAULT 0,
    flipped_at       DATETIME(6) NULL,
    remembered_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_study_card_identity (study_session_id, canonical_id),
    KEY idx_learning_study_card_order (study_session_id, order_index),
    CONSTRAINT fk_learning_study_card_session FOREIGN KEY (study_session_id)
        REFERENCES learning_study_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Micro-check attempt summary per (activity, batch): 3 attempts per cycle, 60-minute cooldown,
-- review gate before a new cycle. Pass is decided here; the immutable per-attempt snapshot stays
-- in learning_quiz_session.
CREATE TABLE learning_micro_check_state (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id  BIGINT UNSIGNED NOT NULL,
    batch_index       INT UNSIGNED NOT NULL DEFAULT 0,
    cycle_number      INT UNSIGNED NOT NULL DEFAULT 1,
    attempts_used     INT UNSIGNED NOT NULL DEFAULT 0,
    passed            TINYINT(1) NOT NULL DEFAULT 0,
    first_score       INT NULL,
    latest_score      INT NULL,
    best_score        INT NULL,
    cooldown_until    DATETIME(6) NULL,
    review_required   TINYINT(1) NOT NULL DEFAULT 0,
    row_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_micro_check_activity_batch (activity_item_id, batch_index),
    CONSTRAINT fk_learning_micro_check_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_micro_check_attempts CHECK (attempts_used <= 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Extend the immutable quiz session for micro-check: scope a snapshot to a specific batch, and
-- allow the V2 micro-check quiz type. Legacy rows keep batch_index NULL; the relaxed CHECK keeps
-- the four legacy types unchanged.
ALTER TABLE learning_quiz_session
    ADD COLUMN batch_index INT UNSIGNED NULL COMMENT '[JLAS-W0193] thu tu batch micro-check; null cho quiz legacy'
        AFTER activity_id;

ALTER TABLE learning_quiz_session DROP CHECK chk_quiz_session_type;
ALTER TABLE learning_quiz_session
    ADD CONSTRAINT chk_quiz_session_type
        CHECK (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK'));
