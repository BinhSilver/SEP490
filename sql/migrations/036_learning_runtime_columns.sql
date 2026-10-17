-- [JLAS-W0108/JLAS-W0113/JLAS-W0120] Columns required by the published Learning entities.
-- Apply to an existing MySQL 8 database before starting the application. This file is
-- additive and re-runnable; it does not load or modify learner/content data.
-- The future consolidated Learning migration 022 checks these columns too, so running
-- that migration later will not add them twice.

SET @learning_column_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary'
              AND column_name = 'learning_meaning_vi'),
    'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_meaning_vi TEXT NULL');
PREPARE learning_column_stmt FROM @learning_column_ddl;
EXECUTE learning_column_stmt;
DEALLOCATE PREPARE learning_column_stmt;

SET @learning_column_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary'
              AND column_name = 'learning_example_ja'),
    'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_ja TEXT NULL');
PREPARE learning_column_stmt FROM @learning_column_ddl;
EXECUTE learning_column_stmt;
DEALLOCATE PREPARE learning_column_stmt;

SET @learning_column_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary'
              AND column_name = 'learning_example_vi'),
    'SELECT 1', 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_vi TEXT NULL');
PREPARE learning_column_stmt FROM @learning_column_ddl;
EXECUTE learning_column_stmt;
DEALLOCATE PREPARE learning_column_stmt;

SET @learning_column_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'learning_activity_member'
              AND column_name = 'content_snapshot_json'),
    'SELECT 1', 'ALTER TABLE learning_activity_member ADD COLUMN content_snapshot_json TEXT NULL');
PREPARE learning_column_stmt FROM @learning_column_ddl;
EXECUTE learning_column_stmt;
DEALLOCATE PREPARE learning_column_stmt;

SET @learning_column_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'learning_quiz_session'
              AND column_name = 'draft_answers_json'),
    'SELECT 1', 'ALTER TABLE learning_quiz_session ADD COLUMN draft_answers_json TEXT NULL');
PREPARE learning_column_stmt FROM @learning_column_ddl;
EXECUTE learning_column_stmt;
DEALLOCATE PREPARE learning_column_stmt;
