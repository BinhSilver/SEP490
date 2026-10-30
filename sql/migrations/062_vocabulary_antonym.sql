-- JMdict antonyms are attached to vocabulary entries because Vietnamese senses use different indexes.
CREATE TABLE IF NOT EXISTS vocabulary_antonym (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id    BIGINT UNSIGNED NOT NULL,
    antonym_text     VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    antonym_reading  VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    antonym_sense    INT NULL,
    source_id        BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_antonym (vocabulary_id, antonym_text, antonym_reading),
    KEY idx_vocabulary_antonym_source (source_id),
    CONSTRAINT fk_vocabulary_antonym_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT fk_vocabulary_antonym_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
