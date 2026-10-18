-- [JLAS-VERB-PRACTICE] Run once in an existing JLAS database before restarting the app.
-- Catalog data is stored as regular SQL rows and columns; no serialized JSON payload is used.
-- Catalog starter rows are kept separately in sql/seeds/verb_practice_catalog.sql.
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
