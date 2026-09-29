-- [JLAS-W0193] Task 7 — Dialogue completion evidence: one row per DIALOGUE activity. accumulated_seconds is
-- only ever incremented by a server-computed, capped (20s/heartbeat), gap-reset (>30s) contribution; the
-- client never supplies a duration or a "completed" flag. completed_at is set exactly once, when both
-- accumulated_seconds >= 180 and reached_bottom_at (a separately persisted event) are true.

CREATE TABLE learning_dialogue_progress (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id       BIGINT UNSIGNED NOT NULL,
    source_revision        VARCHAR(64) NOT NULL,
    accumulated_seconds    INT UNSIGNED NOT NULL DEFAULT 0,
    last_heartbeat_at      DATETIME(6) NULL,
    last_heartbeat_token   VARCHAR(80) NULL,
    reached_bottom_at      DATETIME(6) NULL,
    completed_at           DATETIME(6) NULL,
    row_version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dialogue_progress_activity (activity_item_id),
    CONSTRAINT fk_dialogue_progress_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
