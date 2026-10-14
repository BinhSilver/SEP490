-- [JLAS-W0251] Re-runnable upgrade for existing MySQL databases.
SET NAMES utf8mb4;

-- [JLAS-W0251] Evidence-backed Shadowing question preparation and diagnostic snapshots.
CREATE TABLE IF NOT EXISTS shadowing_preparation_job (
    video_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    levels_csv VARCHAR(40) NOT NULL DEFAULT '', kinds_csv VARCHAR(240) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'IDLE', progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NULL, suggestions_csv VARCHAR(240) NULL, suggestion_hash CHAR(64) NULL,
    targets_csv VARCHAR(300) NULL, capacities_csv VARCHAR(300) NULL,
    analysis_version VARCHAR(40) NULL, evidence_plans_json MEDIUMTEXT NULL,
    suggested_jlpt_level VARCHAR(2) NULL, selected_jlpt_level VARCHAR(2) NULL,
    claim_token CHAR(36) NULL, lease_until TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_preparation_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    INDEX idx_shadowing_preparation_status (status, updated_at),
    UNIQUE KEY uk_shadowing_preparation_claim (claim_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_question_bank (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL, subtitle_id BIGINT UNSIGNED NOT NULL,
    source_ids_json TEXT NOT NULL, kind VARCHAR(40) NOT NULL, level_code VARCHAR(10) NOT NULL,
    prompt_text TEXT NOT NULL, answer_text TEXT NOT NULL, choices_json TEXT NOT NULL, explanation TEXT NULL,
    start_time DECIMAL(10,3) NOT NULL, end_time DECIMAL(10,3) NOT NULL,
    source_hash CHAR(64) NOT NULL, occurrence_index SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    content_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_question_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_question_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_question_content (video_id, content_hash),
    INDEX idx_shadowing_question_pick (video_id, status, level_code, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_question_contribution (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, created_on DATE NOT NULL,
    CONSTRAINT fk_shadowing_contribution_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_contribution_day (video_id, user_id, created_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_question_generation_issue (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL, kind VARCHAR(40) NULL, level_code VARCHAR(10) NULL,
    prompt_text TEXT NULL, answer_text TEXT NULL, choices_json TEXT NULL,
    source_ids_json TEXT NULL, supplied_ids_json TEXT NULL, evidence_json MEDIUMTEXT NULL,
    reason VARCHAR(300) NOT NULL, start_time DECIMAL(10,3) NULL,
    attempt_number TINYINT UNSIGNED NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_generation_issue_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    INDEX idx_shadowing_generation_issue_video (video_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @j023_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job' AND COLUMN_NAME = 'analysis_version');
SET @j023_sql = IF(@j023_exists = 0, 'ALTER TABLE shadowing_preparation_job ADD COLUMN analysis_version VARCHAR(40) NULL', 'DO 0');
PREPARE j023_stmt FROM @j023_sql;
EXECUTE j023_stmt;
DEALLOCATE PREPARE j023_stmt;

SET @j023_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job' AND COLUMN_NAME = 'evidence_plans_json');
SET @j023_sql = IF(@j023_exists = 0, 'ALTER TABLE shadowing_preparation_job ADD COLUMN evidence_plans_json MEDIUMTEXT NULL', 'DO 0');
PREPARE j023_stmt FROM @j023_sql;
EXECUTE j023_stmt;
DEALLOCATE PREPARE j023_stmt;

SET @j023_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_question_generation_issue' AND COLUMN_NAME = 'source_ids_json');
SET @j023_sql = IF(@j023_exists = 0, 'ALTER TABLE shadowing_question_generation_issue ADD COLUMN source_ids_json TEXT NULL', 'DO 0');
PREPARE j023_stmt FROM @j023_sql;
EXECUTE j023_stmt;
DEALLOCATE PREPARE j023_stmt;

SET @j023_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_question_generation_issue' AND COLUMN_NAME = 'supplied_ids_json');
SET @j023_sql = IF(@j023_exists = 0, 'ALTER TABLE shadowing_question_generation_issue ADD COLUMN supplied_ids_json TEXT NULL', 'DO 0');
PREPARE j023_stmt FROM @j023_sql;
EXECUTE j023_stmt;
DEALLOCATE PREPARE j023_stmt;

SET @j023_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_question_generation_issue' AND COLUMN_NAME = 'evidence_json');
SET @j023_sql = IF(@j023_exists = 0, 'ALTER TABLE shadowing_question_generation_issue ADD COLUMN evidence_json MEDIUMTEXT NULL', 'DO 0');
PREPARE j023_stmt FROM @j023_sql;
EXECUTE j023_stmt;
DEALLOCATE PREPARE j023_stmt;
