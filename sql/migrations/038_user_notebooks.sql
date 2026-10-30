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
