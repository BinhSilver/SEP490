-- Store estimated scaled scores by test section for history and review screens.
CREATE TABLE IF NOT EXISTS jlpt_attempt_score_snapshot (
    attempt_id          BIGINT UNSIGNED NOT NULL,
    total_score         SMALLINT UNSIGNED NOT NULL,
    section_scores_json LONGTEXT NOT NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT fk_jlpt_attempt_score_snapshot_attempt FOREIGN KEY (attempt_id)
        REFERENCES jlpt_attempt_history(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
