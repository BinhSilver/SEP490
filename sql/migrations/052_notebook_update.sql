-- [JLAS2-23] Consolidated feature update. Select the intended database before running.
-- Prepared for review only; no SQL is executed by the commit workflow.

-- ===== 038_user_notebooks.sql =====
CREATE TABLE IF NOT EXISTS user_notebook (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(120) NOT NULL,
    notebook_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notebook_name (user_id, name),
    KEY idx_user_notebook_user (user_id),
    CONSTRAINT fk_user_notebook_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_notebook_entry (
    notebook_id BIGINT NOT NULL,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notebook_id, vocabulary_id),
    KEY idx_user_notebook_entry_vocab (vocabulary_id),
    CONSTRAINT fk_user_notebook_entry_notebook FOREIGN KEY (notebook_id) REFERENCES user_notebook(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_notebook_entry_vocab FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO user_notebook (user_id, name, notebook_type)
SELECT id, 'Sổ tay của tôi', 'PERSONAL' FROM users;

INSERT IGNORE INTO user_notebook (user_id, name, notebook_type)
SELECT id, 'Sổ tay điểm yếu', 'WEAKNESS' FROM users;

INSERT IGNORE INTO user_notebook_entry (notebook_id, vocabulary_id, created_at)
SELECT n.id, f.vocabulary_id, f.created_at
FROM dictionary_favorite f
JOIN user_notebook n ON n.user_id = f.user_id AND n.notebook_type = 'PERSONAL';

-- ===== 039_notebook_review.sql =====
-- Run after sql/migrations/038_user_notebooks.sql.
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
