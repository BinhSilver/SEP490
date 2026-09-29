-- [JLAS-W0193] Backward-compatible persistence for Interactive Learning Path V2.
ALTER TABLE user_learning_path
    ADD COLUMN experience_version VARCHAR(30) NOT NULL DEFAULT 'LEGACY_V1' AFTER id,
    ADD CONSTRAINT chk_learning_path_experience
        CHECK (experience_version IN ('LEGACY_V1','INTERACTIVE_V2'));

CREATE TABLE learning_path_package (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    stage_id BIGINT UNSIGNED NULL,
    phase VARCHAR(20) NOT NULL,
    day_number INT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'LOCKED',
    estimated_minutes INT UNSIGNED NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_package_path_day (learning_path_id, day_number),
    UNIQUE KEY uk_learning_package_path_order (learning_path_id, order_index),
    KEY idx_learning_package_status (learning_path_id, status, order_index),
    CONSTRAINT fk_learning_package_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_package_phase CHECK (phase IN ('WARM_UP','GOAL_LEARNING')),
    CONSTRAINT chk_learning_package_status CHECK (status IN ('LOCKED','AVAILABLE','IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_learning_package_minutes CHECK (estimated_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE user_learning_path_item
    ADD COLUMN package_id BIGINT UNSIGNED NULL AFTER learning_path_id,
    ADD COLUMN activity_type VARCHAR(20) NULL AFTER package_id,
    ADD COLUMN activity_state VARCHAR(20) NULL AFTER activity_type,
    ADD UNIQUE KEY uk_learning_path_item_package_type (package_id, activity_type),
    ADD KEY idx_learning_path_item_package_order (package_id, order_index),
    ADD CONSTRAINT fk_learning_path_item_package FOREIGN KEY (package_id)
        REFERENCES learning_path_package(id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_learning_path_item_activity_type CHECK
        (activity_type IS NULL OR activity_type IN ('VOCABULARY','KANJI','GRAMMAR','DIALOGUE')),
    ADD CONSTRAINT chk_learning_path_item_activity_state CHECK
        (activity_state IS NULL OR activity_state IN ('LOCKED','STUDYING','CHECK_READY','PASSED','COOLDOWN','COMPLETED'));
