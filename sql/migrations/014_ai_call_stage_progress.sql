-- [JLAS-W0206] Durable AI Call stage progress and review attempts. Safe to re-run.
SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='stage_code');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN stage_code VARCHAR(100) NULL','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='stage_definition_json');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN stage_definition_json JSON NULL','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='stage_cursor');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN stage_cursor INT NOT NULL DEFAULT 0','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='stage_passed');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN stage_passed BOOLEAN NOT NULL DEFAULT FALSE','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='content_review');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN content_review BOOLEAN NOT NULL DEFAULT FALSE','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='current_state_code');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN current_state_code VARCHAR(80) NULL','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='completed_objectives');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN completed_objectives JSON NULL','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name='last_activity_at');
SET @stage_ddl=IF(@stage_exists=0,'ALTER TABLE conversation_session ADD COLUMN last_activity_at DATETIME(6) NULL','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_index_exists=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='conversation_session' AND index_name='idx_session_stage_progress');
SET @stage_ddl=IF(@stage_index_exists=0,'ALTER TABLE conversation_session ADD KEY idx_session_stage_progress (user_id,scenario_id,stage_code,stage_passed)','SELECT 1');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;
