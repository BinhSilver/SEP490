-- Independent quality gate for imported YouTube captions.
-- Keep jlas_schema.sql unchanged; apply this migration through the deployment process.
CREATE TABLE IF NOT EXISTS subtitle_quality_review (
    subtitle_id             BIGINT UNSIGNED NOT NULL,
    source_type             VARCHAR(32) NOT NULL,
    source_text             TEXT NOT NULL,
    transcription_text      TEXT NOT NULL,
    similarity_score        DECIMAL(5,4) NOT NULL,
    review_status           VARCHAR(32) NOT NULL,
    review_reason           VARCHAR(500) NULL,
    reviewed_by_user_id     BIGINT UNSIGNED NULL,
    reviewed_at             DATETIME(6) NULL,
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (subtitle_id),
    KEY idx_subtitle_quality_status (review_status, subtitle_id),
    KEY idx_subtitle_quality_reviewer (reviewed_by_user_id, reviewed_at),
    CONSTRAINT fk_subtitle_quality_subtitle FOREIGN KEY (subtitle_id)
        REFERENCES subtitle(id) ON DELETE CASCADE,
    CONSTRAINT fk_subtitle_quality_reviewer FOREIGN KEY (reviewed_by_user_id)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
