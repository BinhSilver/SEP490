-- [JLAS-W0250] Apply to existing databases before running the updated application.
-- Earlier dictionary reports had no per-reporter records, so they cannot be backfilled.
CREATE TABLE IF NOT EXISTS dictionary_comment_report (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    comment_id  BIGINT UNSIGNED NOT NULL,
    reporter_id BIGINT UNSIGNED NOT NULL,
    reason      VARCHAR(500) NULL,
    status      VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    resolved_at DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dictionary_comment_report_once (comment_id, reporter_id),
    KEY idx_dictionary_comment_report_open (comment_id, status),
    KEY idx_dictionary_comment_report_reporter (reporter_id),
    CONSTRAINT fk_dictionary_comment_report_comment FOREIGN KEY (comment_id)
        REFERENCES content_comment(id) ON DELETE CASCADE,
    CONSTRAINT fk_dictionary_comment_report_reporter FOREIGN KEY (reporter_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_dictionary_comment_report_status CHECK (status IN ('OPEN','RESOLVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
