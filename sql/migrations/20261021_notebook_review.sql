-- Run after sql/migrations/20261021_user_notebooks.sql.
-- Review state belongs to a word in one notebook, never to the dictionary word globally.
CREATE TABLE IF NOT EXISTS user_notebook_review (
    notebook_id BIGINT NOT NULL,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    ease_factor DECIMAL(4,2) NOT NULL DEFAULT 2.50,
    repetitions INT NOT NULL DEFAULT 0,
    interval_days INT NOT NULL DEFAULT 0,
    due_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_reviewed_at DATETIME NULL,
    PRIMARY KEY (notebook_id, vocabulary_id),
    KEY idx_notebook_review_due (notebook_id, due_at),
    CONSTRAINT fk_notebook_review_entry FOREIGN KEY (notebook_id, vocabulary_id)
        REFERENCES user_notebook_entry (notebook_id, vocabulary_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_notebook_study_setting (
    notebook_id BIGINT NOT NULL,
    daily_new_limit INT NOT NULL DEFAULT 30,
    PRIMARY KEY (notebook_id),
    CONSTRAINT fk_notebook_study_setting_notebook FOREIGN KEY (notebook_id)
        REFERENCES user_notebook (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
