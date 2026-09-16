-- =============================================================================
-- JLAS2-124: additive schema for two-phase learning path scheduling.
-- Target: MySQL 8.0.16+ (jlas_schema.sql baseline, do NOT modify the shared file).
-- Idempotent: safe to run more than once (each change is guarded by a catalog check).
-- =============================================================================

-- 1) Snapshot + scheduling columns on user_learning_path ----------------------

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'survey_id');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN survey_id BIGINT UNSIGNED NULL AFTER current_level',
    'SELECT ''user_learning_path.survey_id already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'assessment_attempt_id');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN assessment_attempt_id BIGINT UNSIGNED NULL AFTER survey_id',
    'SELECT ''user_learning_path.assessment_attempt_id already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'assessment_revision');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN assessment_revision VARCHAR(50) NULL AFTER assessment_attempt_id',
    'SELECT ''user_learning_path.assessment_revision already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'policy_version');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN policy_version VARCHAR(100) NULL AFTER assessment_revision',
    'SELECT ''user_learning_path.policy_version already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'minutes_per_day');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN minutes_per_day INT UNSIGNED NULL AFTER policy_version',
    'SELECT ''user_learning_path.minutes_per_day already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND COLUMN_NAME = 'coverage_gaps_json');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD COLUMN coverage_gaps_json TEXT NULL AFTER minutes_per_day',
    'SELECT ''user_learning_path.coverage_gaps_json already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) Generation-identity and version uniqueness ---------------------------------

SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND INDEX_NAME = 'uk_learning_path_generation');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD UNIQUE KEY uk_learning_path_generation (user_id, assessment_attempt_id, assessment_revision, policy_version)',
    'SELECT ''uk_learning_path_generation already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path'
               AND INDEX_NAME = 'uk_learning_path_user_version');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path ADD UNIQUE KEY uk_learning_path_user_version (user_id, version)',
    'SELECT ''uk_learning_path_user_version already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
