-- JLAS2-19: additive import provenance and batch audit schema.
-- Apply after the canonical vocabulary_entry table exists.

CREATE TABLE IF NOT EXISTS dictionary_source (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code         VARCHAR(50) NOT NULL,
    name         VARCHAR(255) NOT NULL,
    source_url   VARCHAR(1000) NULL,
    license_name VARCHAR(255) NULL,
    license_url  VARCHAR(1000) NULL,
    is_active    TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dictionary_source_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dictionary_import_batch (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_id        BIGINT UNSIGNED NOT NULL,
    source_version   VARCHAR(255) NULL,
    source_checksum  CHAR(64) NOT NULL,
    status           VARCHAR(30) NOT NULL,
    started_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at     DATETIME(6) NULL,
    total_records    BIGINT UNSIGNED NOT NULL DEFAULT 0,
    inserted_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_records  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    skipped_records  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_records   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_message    VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    KEY idx_dictionary_import_identity
        (source_id, source_version, source_checksum, status),
    CONSTRAINT fk_dictionary_import_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id),
    CONSTRAINT chk_dictionary_import_status CHECK
        (status IN ('RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED','SKIPPED')),
    CONSTRAINT chk_dictionary_import_counters CHECK
        (total_records >= 0 AND inserted_records >= 0 AND updated_records >= 0
         AND skipped_records >= 0 AND failed_records >= 0),
    CONSTRAINT chk_dictionary_import_completion CHECK
        (completed_at IS NULL OR completed_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vocabulary_external_reference (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id     BIGINT UNSIGNED NOT NULL,
    source_id         BIGINT UNSIGNED NOT NULL,
    external_entry_id VARCHAR(255) NOT NULL,
    imported_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_external_source (source_id, external_entry_id),
    KEY idx_vocabulary_external_vocabulary (vocabulary_id),
    CONSTRAINT fk_vocabulary_external_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT fk_vocabulary_external_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO dictionary_source
    (code, name, source_url, license_name, license_url, is_active)
VALUES
    ('JMDICT', 'JMdict', 'https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html',
     'Creative Commons Attribution-ShareAlike 4.0',
     'https://www.edrdg.org/edrdg/licence.html', 1),
    ('KANJIDIC2', 'KANJIDIC2', 'https://www.edrdg.org/wiki/KANJIDIC_Project.html',
     'Creative Commons Attribution-ShareAlike 4.0',
     'https://www.edrdg.org/edrdg/licence.html', 1),
    ('KANJIVG', 'KanjiVG', 'https://kanjivg.tagaini.net/',
     'Creative Commons Attribution-ShareAlike 3.0',
     'https://creativecommons.org/licenses/by-sa/3.0/', 1);

-- Manual rollback (destructive; never run by the importer):
-- DROP TABLE vocabulary_external_reference;
-- DROP TABLE dictionary_import_batch;
-- DROP TABLE dictionary_source;
