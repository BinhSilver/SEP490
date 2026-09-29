-- JLAS-W0108 / JLAS-W0127: personalized learning path columns.
-- jlas_schema.sql already declares these; this brings existing databases in line so that
-- spring.jpa.hibernate.ddl-auto=validate passes. Run once.

ALTER TABLE user_learning_path
    ADD COLUMN survey_id             BIGINT UNSIGNED NULL,
    ADD COLUMN assessment_attempt_id BIGINT UNSIGNED NULL,
    ADD COLUMN assessment_revision   VARCHAR(50) NULL,
    ADD COLUMN policy_version        VARCHAR(100) NULL,
    ADD COLUMN minutes_per_day       INT UNSIGNED NULL,
    ADD COLUMN coverage_gaps_json    TEXT NULL,
    ADD UNIQUE KEY uk_learning_path_generation (user_id, assessment_attempt_id, assessment_revision, policy_version),
    ADD UNIQUE KEY uk_learning_path_user_version (user_id, version);

ALTER TABLE user_learning_path_item
    DROP CHECK chk_learning_path_item_kind,
    MODIFY COLUMN kind VARCHAR(20) NULL,
    ADD COLUMN phase             VARCHAR(20) NULL,
    ADD COLUMN reason_code       VARCHAR(50) NULL,
    ADD COLUMN day_number        INT UNSIGNED NULL,
    ADD COLUMN estimated_minutes INT UNSIGNED NULL,
    ADD COLUMN matched_tag_codes TEXT NULL,
    ADD CONSTRAINT chk_learning_path_item_kind CHECK (kind IS NULL OR kind IN ('CURRICULUM','REVIEW'));
