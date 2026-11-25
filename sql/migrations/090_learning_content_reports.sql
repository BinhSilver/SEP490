-- [JLAS2-270] Learning content and displayed AI explanation reports; no automatic hiding or content edits.
-- Run once before enabling the new report API; safe to re-run. Contains no changes to existing content.
CREATE TABLE IF NOT EXISTS learning_content_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_user_id BIGINT NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_code VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
    page_context VARCHAR(40) NOT NULL,
    target_label VARCHAR(255) NOT NULL,
    reason VARCHAR(16) NOT NULL,
    note VARCHAR(1000) NULL,
    context_json LONGTEXT NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    review_note VARCHAR(1000) NULL,
    resolved_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    open_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_report_open (reporter_user_id,target_type,target_code,open_flag),
    KEY idx_learning_report_queue (status,created_at,id),
    KEY idx_learning_report_user_day (reporter_user_id,created_at),
    CONSTRAINT chk_learning_report_status CHECK (status IN ('OPEN','RESOLVED','REJECTED')),
    CONSTRAINT chk_learning_report_target CHECK (target_type IN ('JLPT_QUESTION','VERB_QUESTION','KANJI_MAP','AI_EXPLANATION')),
    CONSTRAINT chk_learning_report_reason CHECK (reason IN ('CONTENT','ANSWER','AUDIO','AI','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
