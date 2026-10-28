-- [JLAS-W0108] Consolidated task-owned Learning migration.

-- Apply after jlas_schema.sql and applicable shared migrations; select and verify the target database first.

-- Re-runnable on MySQL 8. Includes former JLAS-W0193 mock-attempt table and Learning additions formerly numbered 022-025, 051 and 052.

SET NAMES utf8mb4;

-- [JLAS-W0193] Re-runnable live-DB addition for server-verified JLPT Mock Test results.
CREATE TABLE IF NOT EXISTS jlpt_mock_attempt (
    attempt_id BIGINT UNSIGNED NOT NULL,
    exam_code VARCHAR(30) NOT NULL,
    jlpt_level VARCHAR(10) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    correct_count INT UNSIGNED NOT NULL,
    question_count INT UNSIGNED NOT NULL,
    score_percent DECIMAL(5,2) NOT NULL,
    passed TINYINT(1) NOT NULL,
    section_results_json JSON NOT NULL,
    answers_json JSON NOT NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_jlpt_mock_exam (exam_code, jlpt_level),
    CONSTRAINT fk_jlpt_mock_attempt_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_jlpt_mock_attempt_exam FOREIGN KEY (exam_code)
        REFERENCES jlpt_exam(exam_code),
    CONSTRAINT chk_jlpt_mock_level CHECK (jlpt_level IN ('N1','N2','N3','N4','N5')),
    CONSTRAINT chk_jlpt_mock_score CHECK (score_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_jlpt_mock_counts CHECK (correct_count <= question_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- BEGIN sql/migrations/022_learning_vocabulary_context.sql SHA256 1fe0e21a60405b8c0298bb303e57a6d10f0a5bed560efb63f47db3245fcf9f17
-- [JLAS-W0120] Contextual vocabulary display for task-owned learning lessons.
-- Backfill is intentionally absent: existing members and textbook links keep their current meaning.
SET @owned_context_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
         AND table_name = 'content_vocabulary' AND column_name = 'learning_meaning_vi'),
  'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_meaning_vi TEXT NULL');
PREPARE owned_context_stmt FROM @owned_context_ddl; EXECUTE owned_context_stmt; DEALLOCATE PREPARE owned_context_stmt;
SET @owned_context_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
         AND table_name = 'content_vocabulary' AND column_name = 'learning_example_ja'),
  'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_ja TEXT NULL');
PREPARE owned_context_stmt FROM @owned_context_ddl; EXECUTE owned_context_stmt; DEALLOCATE PREPARE owned_context_stmt;
SET @owned_context_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
         AND table_name = 'content_vocabulary' AND column_name = 'learning_example_vi'),
  'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_vi TEXT NULL');
PREPARE owned_context_stmt FROM @owned_context_ddl; EXECUTE owned_context_stmt; DEALLOCATE PREPARE owned_context_stmt;
SET @owned_context_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
         AND table_name = 'learning_activity_member' AND column_name = 'content_snapshot_json'),
  'SELECT 1', 'ALTER TABLE learning_activity_member ADD COLUMN content_snapshot_json TEXT NULL');
PREPARE owned_context_stmt FROM @owned_context_ddl; EXECUTE owned_context_stmt; DEALLOCATE PREPARE owned_context_stmt;
-- END sql/migrations/022_learning_vocabulary_context.sql

-- BEGIN sql/migrations/023_learning_practice_draft_question.sql SHA256 7be221fc0a596c0e0ccc72d1bc651dc97acdb0301c0ce048570ad20079ba2101
-- [JLAS-W0114] AI draft questions are isolated from scored Learning/Assessment banks.
-- Additive and re-runnable on MySQL 8. Select the intended database before executing.
CREATE TABLE IF NOT EXISTS learning_practice_draft_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_code VARCHAR(100) NOT NULL,
    lesson_source_code VARCHAR(100) NOT NULL,
    goal_code VARCHAR(20) NOT NULL,
    jlpt_level VARCHAR(10) NULL,
    grammar_code VARCHAR(64) NOT NULL,
    question_type VARCHAR(16) NOT NULL,
    source_status VARCHAR(20) NOT NULL DEFAULT 'AI_DRAFT',
    review_status VARCHAR(30) NOT NULL DEFAULT 'NEEDS_HUMAN_REVIEW',
    payload_json JSON NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_practice_draft_code (draft_code),
    KEY idx_learning_practice_draft_lesson (lesson_source_code, review_status),
    CONSTRAINT chk_learning_practice_draft_goal
        CHECK (goal_code IN ('JLPT','BA','TRAVEL','DAILY_LIFE')),
    CONSTRAINT chk_learning_practice_draft_type
        CHECK (question_type IN ('MCQ','FILL','ORDER')),
    CONSTRAINT chk_learning_practice_draft_status
        CHECK (source_status = 'AI_DRAFT' AND review_status = 'NEEDS_HUMAN_REVIEW')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- END sql/migrations/023_learning_practice_draft_question.sql

-- BEGIN sql/migrations/024_learning_owned_source_draft.sql SHA256 464257cf9a0aa30ff013393350d3de56320d17fe97bfcfd8304bf3b3e5ddb8da
-- [JLAS-W0108] Additive task-owned holding area. Does not publish lessons,
-- alter source-team textbooks, or write to any scored question bank.
CREATE TABLE IF NOT EXISTS learning_owned_source_draft (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_pack VARCHAR(64) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    goal VARCHAR(20) NOT NULL,
    level VARCHAR(10) NULL,
    part_code VARCHAR(100) NOT NULL,
    item_order INT UNSIGNED NOT NULL,
    audio_state VARCHAR(50) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'NEEDS_HUMAN_REVIEW',
    source_json_sha256 CHAR(64) NOT NULL,
    source_payload JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_owned_draft_source (source_pack, source_id),
    KEY idx_learning_owned_draft_part (part_code, item_order),
    CONSTRAINT chk_learning_owned_draft_type
        CHECK (item_type IN ('VOCABULARY','GRAMMAR','KANJI','DIALOGUE')),
    CONSTRAINT chk_learning_owned_draft_goal
        CHECK (goal IN ('JLPT','BA','TRAVEL','DAILY_LIFE')),
    CONSTRAINT chk_learning_owned_draft_review
        CHECK (review_status = 'NEEDS_HUMAN_REVIEW')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- END sql/migrations/024_learning_owned_source_draft.sql

-- BEGIN sql/migrations/025_learning_quiz_session_draft_answers.sql SHA256 0aa66470e04eaa8392912b902c41df1b45c659792215105da59c965590aba005
-- [JLAS-W0113] Autosave draft for an in-progress micro-check attempt. The column holds the learner's current
-- selections (questionId -> selectedOption; null/absent = unselected) so a reload / re-open / re-login can
-- restore the same attempt's answers, matching and position without creating a new attempt. Written only while
-- status=ISSUED; submit never reads, grades, spends quota or rewards from it. Additive and re-runnable on
-- MySQL 8; select the intended database before executing.
SET @owned_draft_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'learning_quiz_session'
             AND column_name = 'draft_answers_json'),
    'SELECT 1',
    'ALTER TABLE learning_quiz_session ADD COLUMN draft_answers_json TEXT NULL AFTER source_snapshot_json');
PREPARE owned_draft_stmt FROM @owned_draft_ddl;
EXECUTE owned_draft_stmt;
DEALLOCATE PREPARE owned_draft_stmt;
-- END sql/migrations/025_learning_quiz_session_draft_answers.sql

-- [JLAS-W0108] 2026-10-19 (owner decision): communication dialogue situations carry an approximate JLPT level
-- so the roadmap can group them under their topic and order/label them easy -> hard. Re-runnable.
SET @owned_topic_level_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
         AND table_name = 'learning_topic' AND column_name = 'jlpt_level'),
  'SELECT 1', 'ALTER TABLE learning_topic ADD COLUMN jlpt_level VARCHAR(10) NULL AFTER topic_order');
PREPARE owned_topic_level_stmt FROM @owned_topic_level_ddl;
EXECUTE owned_topic_level_stmt;
DEALLOCATE PREPARE owned_topic_level_stmt;

-- [JLAS-W0108] 2026-10-19 (owner C2/C4/G1-G3): J-LAS supplement aids per communication dialogue lesson (furigana,
-- "Từ & cụm từ", optional reply challenge) and the first-clear guard of the one-time 2 CP challenge reward.
-- Additive and re-runnable on MySQL 8; select the intended database before executing.
CREATE TABLE IF NOT EXISTS learning_dialogue_aid (
    lesson_id       BIGINT UNSIGNED NOT NULL,
    aid_json        MEDIUMTEXT NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    source_code     VARCHAR(64) NOT NULL,
    review_status   VARCHAR(20) NOT NULL DEFAULT 'NEEDS_REVIEW',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lesson_id),
    CONSTRAINT fk_dialogue_aid_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_dialogue_aid_review
        CHECK (review_status IN ('NEEDS_REVIEW','APPROVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_dialogue_challenge_clear (
    user_id         BIGINT UNSIGNED NOT NULL,
    lesson_id       BIGINT UNSIGNED NOT NULL,
    correct_count   INT UNSIGNED NOT NULL,
    question_count  INT UNSIGNED NOT NULL,
    cleared_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, lesson_id),
    KEY idx_dialogue_challenge_lesson (lesson_id),
    CONSTRAINT fk_dialogue_challenge_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_dialogue_challenge_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] D3-05 (owner 2026-10-24): a JLPT day is filled by minutes, so the next chapter may start in what is
-- left of the day its previous chapter ended in - two packages of one path can share a day_number.
-- Replaces the unique key uk_learning_package_path_day with a plain index (jlas_schema.sql already has the index).
-- Formerly sql/migrations/052_learning_package_shared_day.sql. Re-runnable; it changes no rows.
SET @learning_package_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'learning_path_package'
              AND index_name = 'idx_learning_package_path_day'),
    'SELECT 1',
    'ALTER TABLE learning_path_package ADD INDEX idx_learning_package_path_day (learning_path_id, day_number)');
PREPARE learning_package_stmt FROM @learning_package_ddl;
EXECUTE learning_package_stmt;
DEALLOCATE PREPARE learning_package_stmt;

SET @learning_package_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'learning_path_package'
              AND index_name = 'uk_learning_package_path_day'),
    'ALTER TABLE learning_path_package DROP INDEX uk_learning_package_path_day',
    'SELECT 1');
PREPARE learning_package_stmt FROM @learning_package_ddl;
EXECUTE learning_package_stmt;
DEALLOCATE PREPARE learning_package_stmt;
