-- [JLAS2-40] Consolidated feature update. Select the intended database before running.
-- Prepared for review only; no SQL is executed by the commit workflow.

-- ===== 036_verb_practice.sql =====
-- [JLAS-VERB-PRACTICE] Run once in the existing JLAS database before restarting.
-- Adds practice history and per-verb/form progress. No existing data is changed.
CREATE TABLE IF NOT EXISTS verb_practice_session (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    mode VARCHAR(20) NOT NULL,
    payload LONGTEXT NOT NULL,
    answered_count INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    INDEX idx_verb_session_user_started (user_id, started_at),
    CONSTRAINT fk_verb_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS verb_practice_progress (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    verb_id VARCHAR(40) NOT NULL,
    form_code VARCHAR(30) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_verb_progress (user_id, verb_id, form_code),
    CONSTRAINT fk_verb_progress_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 037_verb_practice_content_admin.sql =====
-- [JLAS-VERB-PRACTICE] Run once in an existing JLAS database before restarting the app.
-- Catalog data is stored as regular SQL rows and columns; no serialized JSON payload is used.
-- Catalog starter rows are kept separately in sql/seeds/seed_data_verb_practice.sql.
CREATE TABLE IF NOT EXISTS verb_practice_catalog_verb (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    word VARCHAR(80) NOT NULL,
    reading VARCHAR(100) NOT NULL,
    meaning VARCHAR(240) NOT NULL,
    verb_group INT NOT NULL,
    level_code VARCHAR(2) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BIGINT UNSIGNED NULL,
    INDEX idx_verb_catalog_level_group (level_code, verb_group),
    CONSTRAINT fk_verb_catalog_verb_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS verb_practice_catalog_context (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    verb_id VARCHAR(40) NOT NULL,
    form_code VARCHAR(30) NOT NULL,
    sentence VARCHAR(500) NOT NULL,
    translation VARCHAR(500) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BIGINT UNSIGNED NULL,
    INDEX idx_verb_catalog_context_verb (verb_id),
    INDEX idx_verb_catalog_context_form (form_code),
    CONSTRAINT fk_verb_catalog_context_verb FOREIGN KEY (verb_id)
        REFERENCES verb_practice_catalog_verb(id) ON DELETE CASCADE,
    CONSTRAINT fk_verb_catalog_context_updated_by FOREIGN KEY (updated_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
