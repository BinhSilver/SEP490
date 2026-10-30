-- [JLAS-W0125] Split untouched activities within a stable package, without changing stage/reward units.
-- Apply on a backed-up target after 014, BEFORE starting code that maps part_index.
-- No content rows are deleted. Existing activities remain part 1. No USE statement: verify DATABASE().
-- Once multiple parts exist, old application binaries are not an approved rollback target.

SET @jlas_part_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item' AND COLUMN_NAME = 'part_index');
SET @jlas_part_ddl = IF(@jlas_part_column = 0,
    'ALTER TABLE user_learning_path_item ADD COLUMN part_index INT UNSIGNED NOT NULL DEFAULT 1 AFTER activity_state',
    'SELECT 1');
PREPARE jlas_part_stmt FROM @jlas_part_ddl;
EXECUTE jlas_part_stmt;
DEALLOCATE PREPARE jlas_part_stmt;

-- Install the replacement uniqueness before relaxing the legacy uniqueness.
SET @jlas_part_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
      AND INDEX_NAME = 'uk_learning_path_item_package_part');
SET @jlas_part_ddl = IF(@jlas_part_key = 0,
    'ALTER TABLE user_learning_path_item ADD UNIQUE KEY uk_learning_path_item_package_part (package_id, activity_type, part_index)',
    'SELECT 1');
PREPARE jlas_part_stmt FROM @jlas_part_ddl;
EXECUTE jlas_part_stmt;
DEALLOCATE PREPARE jlas_part_stmt;

SET @jlas_old_part_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
      AND INDEX_NAME = 'uk_learning_path_item_package_type');
SET @jlas_part_ddl = IF(@jlas_old_part_key > 0,
    'ALTER TABLE user_learning_path_item DROP INDEX uk_learning_path_item_package_type',
    'SELECT 1');
PREPARE jlas_part_stmt FROM @jlas_part_ddl;
EXECUTE jlas_part_stmt;
DEALLOCATE PREPARE jlas_part_stmt;
