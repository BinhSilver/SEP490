-- [JLAS2-34] Filter published, lookup-visible daily words by target JLPT level.
-- Re-runnable for databases created before the composite index was added to jlas_schema.sql.
SET @daily_level_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'vocabulary_entry'
      AND index_name = 'idx_vocabulary_daily_level');
SET @daily_level_ddl = IF(@daily_level_index_exists = 0,
    'ALTER TABLE vocabulary_entry ADD INDEX idx_vocabulary_daily_level (jlpt_level, dictionary_status, usage_scope)',
    'SELECT 1');
PREPARE daily_level_stmt FROM @daily_level_ddl;
EXECUTE daily_level_stmt;
DEALLOCATE PREPARE daily_level_stmt;
