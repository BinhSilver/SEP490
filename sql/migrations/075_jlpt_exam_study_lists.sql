-- [JLAS2-270] Tables for related vocabulary and grammar; MySQL 8.
CREATE TABLE IF NOT EXISTS jlpt_exam_study_list (
    exam_id BIGINT UNSIGNED NOT NULL,
    metadata JSON NOT NULL,
    import_batch_id BINARY(16) NULL,
    PRIMARY KEY (exam_id),
    KEY idx_jlpt_study_list_import_batch (import_batch_id),
    CONSTRAINT fk_jlpt_study_list_exam FOREIGN KEY (exam_id)
        REFERENCES jlpt_exam (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS jlpt_exam_study_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id BIGINT UNSIGNED NOT NULL,
    item_kind ENUM('VOCABULARY','GRAMMAR','UNMATCHED_GRAMMAR') NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    payload JSON NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jlpt_study_item_order (exam_id, item_kind, sort_order),
    CONSTRAINT fk_jlpt_study_item_list FOREIGN KEY (exam_id)
        REFERENCES jlpt_exam_study_list (exam_id) ON DELETE CASCADE,
    CONSTRAINT chk_jlpt_study_item_order CHECK (sort_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
