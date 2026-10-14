-- [JLAS-W0260] Kana Connect: safe to rerun against an existing installation.
CREATE TABLE IF NOT EXISTS daily_kana_challenge (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    challenge_date DATE NOT NULL,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    jlpt_level VARCHAR(10) NOT NULL,
    mixed_levels BOOLEAN NOT NULL DEFAULT FALSE,
    expression VARCHAR(255) NOT NULL,
    reading VARCHAR(255) NOT NULL,
    meaning TEXT NOT NULL,
    cells_json TEXT NOT NULL,
    solution_json TEXT NOT NULL,
    hint_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    elapsed_seconds INT NULL,
    UNIQUE KEY uk_daily_kana_user_date (user_id, challenge_date),
    CONSTRAINT fk_daily_kana_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
