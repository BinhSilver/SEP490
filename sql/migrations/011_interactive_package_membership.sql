-- [JLAS-W0193] Phase 1 is nullable for compatibility with V2 package rows created before this migration.
-- The composer requires both values for every new package; a later audited backfill may tighten NOT NULL.
ALTER TABLE learning_path_package
    ADD COLUMN source_lesson_id BIGINT UNSIGNED NULL AFTER learning_path_id,
    ADD COLUMN content_revision VARCHAR(64) NULL AFTER source_lesson_id,
    ADD UNIQUE KEY uk_learning_package_path_source (learning_path_id, source_lesson_id),
    ADD CONSTRAINT fk_learning_package_source_lesson FOREIGN KEY (source_lesson_id)
        REFERENCES lesson(id) ON DELETE RESTRICT;

CREATE TABLE learning_activity_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id BIGINT UNSIGNED NOT NULL,
    member_type VARCHAR(30) NOT NULL,
    canonical_id BIGINT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_activity_member_identity (activity_item_id, member_type, canonical_id),
    KEY idx_learning_activity_member_order (activity_item_id, order_index),
    CONSTRAINT fk_learning_activity_member_item FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_activity_member_type CHECK
        (member_type IN ('VOCABULARY_ENTRY','KANJI','GRAMMAR_POINT','DIALOGUE_LINE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
