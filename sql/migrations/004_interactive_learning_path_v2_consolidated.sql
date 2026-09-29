-- [JLAS-W0108/W0193] Consolidated migration — replaces the former 004-017 sequence.
-- Applies the FINAL state directly (skips intermediate ADD/DROP CHECK churn that only existed
-- because these landed as 14 separate incremental patches); the result is byte-for-byte the same
-- end state as jlas_schema.sql already documents for these objects. Safe for a fresh
-- drop-and-recreate database — do not run this against a database that already ran the old
-- 004-017 files individually (it will fail on duplicate columns/tables).
--
-- Covers: learning path item study/assessment progress split (P2), nullable lesson_id (P2c),
-- warm-up flashcard membership version (P2d), per-activity quiz anchor (P4), multi-stage paths
-- (P6), path completion timestamp, and the full Interactive Learning Path V2 persistence layer
-- (packages, activity membership, study/micro-check state, grammar practice, dialogue progress,
-- package completion score, Reading/Listening comprehension activities).

SET NAMES utf8mb4;

-- --- P2: activity progress independent of source lesson ---------------------------------------
ALTER TABLE user_learning_path_item
    ADD COLUMN study_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN assessment_status VARCHAR(20) NOT NULL DEFAULT 'NOT_READY';

ALTER TABLE user_learning_path_item
    ADD CONSTRAINT chk_learning_path_item_study_status
        CHECK (study_status IN ('NOT_STARTED','IN_PROGRESS','STUDIED')),
    ADD CONSTRAINT chk_learning_path_item_assessment_status
        CHECK (assessment_status IN ('NOT_READY','READY','ISSUED','SUBMITTED','PASSED','NEEDS_REVIEW'));

-- Backfill lịch sử cũ: item từng được hoàn thành qua lesson_progress (mô hình cũ) -> STUDIED.
UPDATE user_learning_path_item i
JOIN lesson_progress p ON p.lesson_id = i.lesson_id AND p.status = 'COMPLETED'
SET i.study_status = 'STUDIED';

-- --- P2c: lesson_id nullable so an activity can anchor to grammar/vocab/content_item instead ---
ALTER TABLE user_learning_path_item
    MODIFY lesson_id BIGINT UNSIGNED NULL;

-- --- P2d: warm-up flashcard membership fingerprint ----------------------------------------------
ALTER TABLE user_learning_path
    ADD COLUMN warm_up_flashcard_version TEXT NULL;

-- --- P4: per-activity quiz anchor (warm-up grammar quiz keyed by grammar_point, not lesson) -----
ALTER TABLE learning_quiz_session
    ADD COLUMN activity_id BIGINT UNSIGNED NULL COMMENT '[JLAS-W0108] grammarPointId cho GRAMMAR quiz; null cho loai khac';

-- --- P6: multi-stage paths ------------------------------------------------------------------------
ALTER TABLE user_learning_path_item
    ADD COLUMN stage_id BIGINT UNSIGNED NULL;

CREATE TABLE user_learning_stage (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id  BIGINT UNSIGNED NOT NULL,
    level             VARCHAR(10) NOT NULL,
    stage_index       INT UNSIGNED NOT NULL,
    curriculum_version VARCHAR(100) NULL,
    assessment_cycle  VARCHAR(50) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_stage_order (learning_path_id, stage_index),
    KEY idx_learning_stage_path (learning_path_id),
    CONSTRAINT fk_learning_stage_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_stage_level CHECK (level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_learning_stage_status CHECK (status IN ('PENDING','ACTIVE','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --- Path completion timestamp -------------------------------------------------------------------
ALTER TABLE user_learning_path
    ADD COLUMN completed_at DATETIME(6) NULL;

ALTER TABLE user_learning_path
    DROP CHECK chk_learning_path_status,
    ADD CONSTRAINT chk_learning_path_status CHECK (status IN ('ACTIVE','SUPERSEDED','COMPLETED'));

-- --- Interactive Learning Path V2 persistence -----------------------------------------------------
ALTER TABLE user_learning_path
    ADD COLUMN experience_version VARCHAR(30) NOT NULL DEFAULT 'LEGACY_V1' AFTER id,
    ADD CONSTRAINT chk_learning_path_experience
        CHECK (experience_version IN ('LEGACY_V1','INTERACTIVE_V2'));

CREATE TABLE learning_path_package (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    stage_id BIGINT UNSIGNED NULL,
    source_lesson_id BIGINT UNSIGNED NULL,
    content_revision VARCHAR(64) NULL,
    phase VARCHAR(20) NOT NULL,
    day_number INT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'LOCKED',
    estimated_minutes INT UNSIGNED NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    score_percent INT UNSIGNED NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_package_path_day (learning_path_id, day_number),
    UNIQUE KEY uk_learning_package_path_order (learning_path_id, order_index),
    UNIQUE KEY uk_learning_package_path_source (learning_path_id, source_lesson_id),
    KEY idx_learning_package_status (learning_path_id, status, order_index),
    CONSTRAINT fk_learning_package_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_package_source_lesson FOREIGN KEY (source_lesson_id)
        REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT chk_learning_package_phase CHECK (phase IN ('WARM_UP','GOAL_LEARNING')),
    CONSTRAINT chk_learning_package_status CHECK (status IN ('LOCKED','AVAILABLE','IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_learning_package_minutes CHECK (estimated_minutes > 0),
    CONSTRAINT chk_learning_package_score CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE user_learning_path_item
    ADD COLUMN package_id BIGINT UNSIGNED NULL AFTER learning_path_id,
    ADD COLUMN activity_type VARCHAR(20) NULL AFTER package_id,
    ADD COLUMN activity_state VARCHAR(20) NULL AFTER activity_type,
    ADD UNIQUE KEY uk_learning_path_item_package_type (package_id, activity_type),
    ADD KEY idx_learning_path_item_package_order (package_id, order_index),
    ADD CONSTRAINT fk_learning_path_item_package FOREIGN KEY (package_id)
        REFERENCES learning_path_package(id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_learning_path_item_activity_type CHECK
        -- Interactive V2 comprehension activities reference canonical assessment questions.
        (activity_type IS NULL OR activity_type IN ('VOCABULARY','KANJI','GRAMMAR','DIALOGUE','READING','LISTENING')),
    ADD CONSTRAINT chk_learning_path_item_activity_state CHECK
        (activity_state IS NULL OR activity_state IN ('LOCKED','STUDYING','CHECK_READY','PASSED','COOLDOWN','COMPLETED'));

-- Immutable canonical membership; content remains dictionary-owned.
CREATE TABLE learning_activity_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id BIGINT UNSIGNED NOT NULL,
    member_type VARCHAR(30) NOT NULL,
    canonical_id BIGINT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_activity_member_identity (activity_item_id, member_type, canonical_id),
    KEY idx_learning_activity_member_order (activity_item_id, order_index),
    CONSTRAINT fk_learning_activity_member_item FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_activity_member_type CHECK
        (member_type IN ('VOCABULARY_ENTRY','KANJI','GRAMMAR_POINT','DIALOGUE_LINE','COMPREHENSION_QUESTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Vocabulary/Kanji flashcard study sessions: one per activity, resumable, replan-safe.
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
-- allow the V2 micro-check quiz types (Vocabulary/Kanji/Grammar/Reading/Listening).
ALTER TABLE learning_quiz_session
    ADD COLUMN batch_index INT UNSIGNED NULL COMMENT '[JLAS-W0193] thu tu batch micro-check; null cho quiz legacy'
        AFTER activity_id,
    DROP CHECK chk_quiz_session_type,
    ADD CONSTRAINT chk_quiz_session_type CHECK
        (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK',
            'GRAMMAR_MICRO_CHECK','READING_MICRO_CHECK','LISTENING_MICRO_CHECK'));

-- Grammar ordering/fill practice-gate progress, one row per (activity, grammar point).
CREATE TABLE learning_grammar_practice_progress (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id  BIGINT UNSIGNED NOT NULL,
    grammar_point_id  BIGINT UNSIGNED NOT NULL,
    source_revision   VARCHAR(64) NOT NULL,
    ordering_done     TINYINT(1) NOT NULL DEFAULT 0,
    fill_done         TINYINT(1) NOT NULL DEFAULT 0,
    ordering_done_at  DATETIME(6) NULL,
    fill_done_at      DATETIME(6) NULL,
    row_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_grammar_practice_identity (activity_item_id, grammar_point_id),
    CONSTRAINT fk_grammar_practice_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dialogue completion evidence: one row per DIALOGUE activity. accumulated_seconds is only ever
-- incremented by a server-computed, capped (20s/heartbeat), gap-reset (>30s) contribution; the
-- client never supplies a duration or a "completed" flag. completed_at is set exactly once, when
-- both accumulated_seconds >= 180 and reached_bottom_at are true.
CREATE TABLE learning_dialogue_progress (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id       BIGINT UNSIGNED NOT NULL,
    source_revision        VARCHAR(64) NOT NULL,
    accumulated_seconds    INT UNSIGNED NOT NULL DEFAULT 0,
    last_heartbeat_at      DATETIME(6) NULL,
    last_heartbeat_token   VARCHAR(80) NULL,
    reached_bottom_at      DATETIME(6) NULL,
    completed_at           DATETIME(6) NULL,
    row_version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dialogue_progress_activity (activity_item_id),
    CONSTRAINT fk_dialogue_progress_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
