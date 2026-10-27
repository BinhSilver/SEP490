-- [JLAS2-269] Consolidated feature update. Select the intended database before running.
-- Prepared for review only; no SQL is executed by the commit workflow.

-- ===== 20261014_jlpt_practice_type_admin.sql =====
-- Run once in an existing JLAS database before using /admin/jlpt/practice-types.
-- Question counts/content remain in jlpt_* tables; this table stores only admin overrides.
CREATE TABLE IF NOT EXISTS jlpt_practice_type_config (
    level_code VARCHAR(2) NOT NULL,
    type_code VARCHAR(50) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    name_vi VARCHAR(120) NULL,
    name_ja VARCHAR(120) NULL,
    order_index INT UNSIGNED NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by BIGINT UNSIGNED NULL,
    PRIMARY KEY (level_code, type_code),
    KEY idx_jlpt_practice_type_enabled_order (level_code, is_enabled, order_index),
    CONSTRAINT chk_jlpt_practice_type_level CHECK (level_code IN ('N1','N2','N3','N4','N5')),
    CONSTRAINT fk_jlpt_practice_type_updated_by FOREIGN KEY (updated_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 042_jlpt_target_vocabulary.sql =====
USE jlas;
SET NAMES utf8mb4;

SET @jlpt_schema = DATABASE();
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD COLUMN target_vocabulary_id BIGINT UNSIGNED NULL AFTER explanation',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema=@jlpt_schema AND table_name='jlpt_question' AND column_name='target_vocabulary_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD INDEX idx_jlpt_question_target_vocabulary (target_vocabulary_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema=@jlpt_schema AND table_name='jlpt_question' AND index_name='idx_jlpt_question_target_vocabulary'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD CONSTRAINT fk_jlpt_question_target_vocabulary FOREIGN KEY (target_vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE SET NULL',
        'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema=@jlpt_schema AND table_name='jlpt_question' AND constraint_name='fk_jlpt_question_target_vocabulary'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
