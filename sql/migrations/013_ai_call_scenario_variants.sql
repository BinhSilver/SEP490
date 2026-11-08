-- [JLAS-W0205] Apply after the original base schema, for both fresh and existing databases.
-- Run against the intended database; no USE statement. Safe to run again.
SET @aicall_parent_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'scenario' AND column_name = 'parent_scenario_id');
SET @aicall_parent_ddl = IF(@aicall_parent_exists = 0,
    'ALTER TABLE scenario ADD COLUMN parent_scenario_id BIGINT UNSIGNED NULL, ADD KEY idx_scenario_parent (parent_scenario_id,is_active), ADD CONSTRAINT fk_scenario_parent FOREIGN KEY (parent_scenario_id) REFERENCES scenario(id)',
    'SELECT 1');
PREPARE aicall_parent_stmt FROM @aicall_parent_ddl;
EXECUTE aicall_parent_stmt;
DEALLOCATE PREPARE aicall_parent_stmt;

SET @aicall_title_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'scenario' AND column_name = 'variant_title');
SET @aicall_title_ddl = IF(@aicall_title_exists = 0,
    'ALTER TABLE scenario ADD COLUMN variant_title VARCHAR(200) NOT NULL DEFAULT ''Tình huống thông thường''',
    'SELECT 1');
PREPARE aicall_title_stmt FROM @aicall_title_ddl;
EXECUTE aicall_title_stmt;
DEALLOCATE PREPARE aicall_title_stmt;
