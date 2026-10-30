-- [JLAS-W0250] Avatar moderation and private appeal evidence. MySQL 8.0+, re-runnable.
-- Apply to an existing database before starting the updated app (ddl-auto=validate).
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS avatar_submission (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    storage_key   VARCHAR(100) NOT NULL,
    original_name VARCHAR(200) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_avatar_submission_storage (storage_key),
    KEY idx_avatar_submission_user (user_id, id),
    CONSTRAINT fk_avatar_submission_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_avatar_submission_status CHECK (status IN ('SAFE','REVIEW','BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @j022_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_case' AND COLUMN_NAME = 'evidence_file_key');
SET @j022_sql = IF(@j022_column_exists = 0,
    'ALTER TABLE review_case ADD COLUMN evidence_file_key VARCHAR(100) NULL', 'DO 0');
PREPARE j022_stmt FROM @j022_sql;
EXECUTE j022_stmt;
DEALLOCATE PREPARE j022_stmt;

SET @j022_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_case' AND COLUMN_NAME = 'evidence_file_name');
SET @j022_sql = IF(@j022_column_exists = 0,
    'ALTER TABLE review_case ADD COLUMN evidence_file_name VARCHAR(200) NULL', 'DO 0');
PREPARE j022_stmt FROM @j022_sql;
EXECUTE j022_stmt;
DEALLOCATE PREPARE j022_stmt;

SET @j022_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_case' AND COLUMN_NAME = 'evidence_file_type');
SET @j022_sql = IF(@j022_column_exists = 0,
    'ALTER TABLE review_case ADD COLUMN evidence_file_type VARCHAR(100) NULL', 'DO 0');
PREPARE j022_stmt FROM @j022_sql;
EXECUTE j022_stmt;
DEALLOCATE PREPARE j022_stmt;

SET @j022_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_case' AND COLUMN_NAME = 'evidence_file_size');
SET @j022_sql = IF(@j022_column_exists = 0,
    'ALTER TABLE review_case ADD COLUMN evidence_file_size BIGINT NULL', 'DO 0');
PREPARE j022_stmt FROM @j022_sql;
EXECUTE j022_stmt;
DEALLOCATE PREPARE j022_stmt;
