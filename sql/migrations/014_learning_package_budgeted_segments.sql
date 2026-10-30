-- [JLAS-W0193] Daily workload: permit one curriculum lesson to continue in ordered day segments.
-- Existing packages retain segment 1; this migration is safe after either the old or the updated schema.

SET @jlas_has_segment_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'learning_path_package'
      AND COLUMN_NAME = 'source_segment_index'
);
SET @jlas_add_segment_column = IF(@jlas_has_segment_column = 0,
    'ALTER TABLE learning_path_package ADD COLUMN source_segment_index INT UNSIGNED NOT NULL DEFAULT 1 AFTER source_lesson_id',
    'SELECT 1');
PREPARE jlas_add_segment_column_stmt FROM @jlas_add_segment_column;
EXECUTE jlas_add_segment_column_stmt;
DEALLOCATE PREPARE jlas_add_segment_column_stmt;

SET @jlas_has_old_source_index = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'learning_path_package'
      AND INDEX_NAME = 'uk_learning_package_path_source'
);
SET @jlas_drop_old_source_index = IF(@jlas_has_old_source_index > 0,
    'ALTER TABLE learning_path_package DROP INDEX uk_learning_package_path_source',
    'SELECT 1');
PREPARE jlas_drop_old_source_index_stmt FROM @jlas_drop_old_source_index;
EXECUTE jlas_drop_old_source_index_stmt;
DEALLOCATE PREPARE jlas_drop_old_source_index_stmt;

SET @jlas_has_segment_source_index = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'learning_path_package'
      AND INDEX_NAME = 'uk_learning_package_path_source_segment'
);
SET @jlas_add_segment_source_index = IF(@jlas_has_segment_source_index = 0,
    'ALTER TABLE learning_path_package ADD UNIQUE KEY uk_learning_package_path_source_segment (learning_path_id, source_lesson_id, source_segment_index)',
    'SELECT 1');
PREPARE jlas_add_segment_source_index_stmt FROM @jlas_add_segment_source_index;
EXECUTE jlas_add_segment_source_index_stmt;
DEALLOCATE PREPARE jlas_add_segment_source_index_stmt;
