-- =============================================================================
-- JLAS2-124: additive schema for two-phase learning path items.
-- Target: MySQL 8.0.16+ (jlas_schema.sql baseline, do NOT modify the shared file).
-- Idempotent: safe to run more than once (each change is guarded by a catalog check).
-- =============================================================================

-- 1) Phase + schedule columns on user_learning_path_item ----------------------

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'phase');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN phase VARCHAR(20) NULL AFTER kind',
    'SELECT ''user_learning_path_item.phase already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'reason_code');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN reason_code VARCHAR(50) NULL AFTER phase',
    'SELECT ''user_learning_path_item.reason_code already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'day_number');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN day_number INT UNSIGNED NULL AFTER reason_code',
    'SELECT ''user_learning_path_item.day_number already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'estimated_minutes');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN estimated_minutes INT UNSIGNED NULL AFTER day_number',
    'SELECT ''user_learning_path_item.estimated_minutes already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'matched_tag_codes');
SET @sql := IF(@has = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN matched_tag_codes TEXT NULL AFTER estimated_minutes',
    'SELECT ''user_learning_path_item.matched_tag_codes already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) Legacy `kind` becomes nullable (new two-phase items use `phase` instead) ----

SET @nullable := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
               AND COLUMN_NAME = 'kind' AND IS_NULLABLE = 'YES');
SET @sql := IF(@nullable = 0,
    'ALTER TABLE user_learning_path_item MODIFY kind VARCHAR(20) NULL',
    'SELECT ''user_learning_path_item.kind already nullable''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
