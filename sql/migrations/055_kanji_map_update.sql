-- [JLAS2-kanji-map] Consolidated feature update. Select the intended database before running.
-- Prepared for review only; no SQL is executed by the commit workflow.

-- ===== Kanji catalog (consolidated) =====
-- [JLAS-KANJI-MAP] Relational source catalog. Apply before seed_data_kanji_map.sql.
-- Only adds catalog tables; does not alter dictionary data, editorial overlays or learner progress.
CREATE TABLE IF NOT EXISTS kanji_map_dataset (
    id SMALLINT NOT NULL PRIMARY KEY,
    graph_version INT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    sources_json LONGTEXT NOT NULL,
    node_count INT NOT NULL,
    CONSTRAINT chk_kanji_map_dataset_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_node (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    source_order INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_kanji BOOLEAN NOT NULL,
    is_radical BOOLEAN NOT NULL,
    radical_number INT NULL,
    jlpt_level VARCHAR(2) NULL,
    han_viet VARCHAR(255) NULL,
    meaning_vi TEXT NULL,
    meaning_vi_source VARCHAR(100) NULL,
    meaning_vi_source_url VARCHAR(1000) NULL,
    memory_hint TEXT NULL,
    stroke_count INT NULL,
    parent_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    parent_relation VARCHAR(30) NULL,
    radical_name_vi VARCHAR(100) NULL,
    radical_base VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    is_radical_variant BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_kanji_map_level (is_active, jlpt_level),
    CONSTRAINT fk_kanji_map_parent FOREIGN KEY (parent_character) REFERENCES kanji_map_node(kanji_character),
    CONSTRAINT fk_kanji_map_radical_base FOREIGN KEY (radical_base) REFERENCES kanji_map_node(kanji_character),
    CONSTRAINT chk_kanji_map_node_level CHECK (jlpt_level IS NULL OR jlpt_level IN ('N1','N2','N3','N4','N5'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_reading (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reading_type VARCHAR(3) NOT NULL,
    reading_order INT NOT NULL,
    reading_text VARCHAR(100) NOT NULL,
    PRIMARY KEY (kanji_character, reading_type, reading_order),
    CONSTRAINT fk_kanji_map_reading_node FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE,
    CONSTRAINT chk_kanji_map_reading_type CHECK (reading_type IN ('ON','KUN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_gloss (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    gloss_order INT NOT NULL,
    meaning_en TEXT NOT NULL,
    PRIMARY KEY (kanji_character, gloss_order),
    CONSTRAINT fk_kanji_map_gloss_node FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_radical_alias (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    alias_order INT NOT NULL,
    alias_text VARCHAR(100) NOT NULL,
    PRIMARY KEY (kanji_character, alias_order),
    CONSTRAINT fk_kanji_map_alias_node FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_component (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    component_order INT NOT NULL,
    component_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    position_code VARCHAR(30) NULL,
    is_partial BOOLEAN NOT NULL,
    radical_type VARCHAR(30) NULL,
    PRIMARY KEY (kanji_character, component_order),
    KEY idx_kanji_map_component_character (component_character),
    CONSTRAINT fk_kanji_map_component_owner FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE,
    CONSTRAINT fk_kanji_map_component_target FOREIGN KEY (component_character) REFERENCES kanji_map_node(kanji_character)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nested source elements may include glyphs outside the navigable catalog. Preserve their labels as-is.
CREATE TABLE IF NOT EXISTS kanji_map_contained_element (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    element_order INT NOT NULL,
    element_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (kanji_character, element_order),
    CONSTRAINT fk_kanji_map_contained_owner FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kanji_map_stroke (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    stroke_order INT NOT NULL,
    path_data TEXT NOT NULL,
    PRIMARY KEY (kanji_character, stroke_order),
    CONSTRAINT fk_kanji_map_stroke_node FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== Remove unused catalog fields =====
-- [JLAS-KANJI-MAP] Remove source-only fields that no learning/admin feature consumes.
-- Archive remains in data/kanji-map/graph.json; the seed can reconstruct all retained data.
-- Deploy with the matching repository and seed. Editorial content and mastery are untouched.
DROP TABLE IF EXISTS kanji_map_contained_element;

-- MySQL 8 has no DROP COLUMN IF EXISTS. Keep this migration safe to run repeatedly.
SET @kanji_map_trim_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='kanji_map_component' AND column_name='radical_type'),
    'ALTER TABLE kanji_map_component DROP COLUMN radical_type',
    'SELECT 1');
PREPARE kanji_map_trim_statement FROM @kanji_map_trim_sql;
EXECUTE kanji_map_trim_statement;
DEALLOCATE PREPARE kanji_map_trim_statement;

-- ===== Kanji mastery =====
-- [JLAS-KANJI-MAP] Apply once to existing installations; re-runnable.
CREATE TABLE IF NOT EXISTS user_kanji_mastery (
    user_id BIGINT UNSIGNED NOT NULL,
    kanji_character VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    mastered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, kanji_character),
    CONSTRAINT fk_kanji_mastery_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== Editorial Kanji content =====
-- [JLAS-KANJI-MAP] Editorial content for Content Creator; source geometry stays in graph.json.
CREATE TABLE IF NOT EXISTS kanji_map_content (
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    content_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (kanji_character),
    CONSTRAINT chk_kanji_map_content_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== Kanji notebook membership =====
-- [JLAS-KANJI-MAP] Requires notebook setup (038/039 or 052_notebook_update.sql); catalog tables are created above.
-- Reuses user_notebook; Kanji are separate from vocabulary entries and their SRS progress.
CREATE TABLE IF NOT EXISTS user_notebook_kanji (
    notebook_id BIGINT NOT NULL,
    kanji_character VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- [JLAS-KANJI-MAP] Review progress stays with this Kanji in this notebook.
    first_seen_at DATETIME NULL,
    last_reviewed_at DATETIME NULL,
    due_at DATETIME NULL,
    ease_factor DECIMAL(4,2) NOT NULL DEFAULT 2.50,
    repetitions INT NOT NULL DEFAULT 0,
    interval_days INT NOT NULL DEFAULT 0,
    PRIMARY KEY (notebook_id, kanji_character),
    KEY idx_notebook_kanji_due (notebook_id, due_at),
    CONSTRAINT fk_notebook_kanji_book FOREIGN KEY (notebook_id) REFERENCES user_notebook(id) ON DELETE CASCADE,
    CONSTRAINT fk_notebook_kanji_node FOREIGN KEY (kanji_character) REFERENCES kanji_map_node(kanji_character)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-KANJI-MAP-STUDY] Add review state to existing memberships; safe to re-run.
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='first_seen_at'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN first_seen_at DATETIME NULL');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='last_reviewed_at'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN last_reviewed_at DATETIME NULL');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='due_at'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN due_at DATETIME NULL');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='ease_factor'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN ease_factor DECIMAL(4,2) NOT NULL DEFAULT 2.50');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='repetitions'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN repetitions INT NOT NULL DEFAULT 0');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND column_name='interval_days'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD COLUMN interval_days INT NOT NULL DEFAULT 0');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
SET @kanji_study_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user_notebook_kanji' AND index_name='idx_notebook_kanji_due'), 'SELECT 1', 'ALTER TABLE user_notebook_kanji ADD INDEX idx_notebook_kanji_due (notebook_id,due_at)');
PREPARE kanji_study_stmt FROM @kanji_study_ddl;
EXECUTE kanji_study_stmt;
DEALLOCATE PREPARE kanji_study_stmt;
