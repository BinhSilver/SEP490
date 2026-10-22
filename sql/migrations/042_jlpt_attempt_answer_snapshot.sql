-- Preserve the selected answers for submitted attempts so the owner can review them later.
CREATE TABLE IF NOT EXISTS jlpt_attempt_answer_snapshot (
    attempt_id          BIGINT UNSIGNED NOT NULL,
    answers_json        LONGTEXT NOT NULL,
    sentence_orders_json LONGTEXT NOT NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT fk_jlpt_attempt_snapshot_attempt FOREIGN KEY (attempt_id)
        REFERENCES jlpt_attempt_history(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
