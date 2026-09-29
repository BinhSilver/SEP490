CREATE TABLE IF NOT EXISTS dictionary_source (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(50) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  source_url VARCHAR(1000), license_name VARCHAR(255), license_url VARCHAR(1000),
  is_active TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dictionary_import_batch (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  source_id BIGINT UNSIGNED NOT NULL,
  source_version VARCHAR(255), source_checksum CHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL, started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at DATETIME(6), total_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
  inserted_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
  skipped_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_records BIGINT UNSIGNED NOT NULL DEFAULT 0, error_message VARCHAR(2000),
  CONSTRAINT fk_test_batch_source FOREIGN KEY (source_id) REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS radical (
  id SMALLINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  radical_number SMALLINT UNSIGNED NOT NULL UNIQUE,
  `character` VARCHAR(8) NOT NULL UNIQUE,
  name_ja VARCHAR(100), name_vi VARCHAR(100), meaning_vi VARCHAR(255), stroke_count TINYINT UNSIGNED
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kanji (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  `character` VARCHAR(8) NOT NULL UNIQUE, unicode_code VARCHAR(10) NOT NULL UNIQUE,
  radical_id SMALLINT UNSIGNED, stroke_count TINYINT UNSIGNED,
  han_viet VARCHAR(255), meaning_vi TEXT, jlpt_level VARCHAR(10), stroke_svg_uri VARCHAR(500),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_test_kanji_radical FOREIGN KEY (radical_id) REFERENCES radical(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kanji_reading (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, kanji_id BIGINT UNSIGNED NOT NULL,
  reading_type VARCHAR(20) NOT NULL, reading_text VARCHAR(100) NOT NULL,
  is_common TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_test_kanji_reading (kanji_id, reading_type, reading_text),
  CONSTRAINT fk_test_reading_kanji FOREIGN KEY (kanji_id) REFERENCES kanji(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_entry (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  expression VARCHAR(255) NOT NULL, normalized_expression VARCHAR(255) NOT NULL,
  reading VARCHAR(255) NOT NULL, normalized_reading VARCHAR(255) NOT NULL,
  reading_romaji VARCHAR(255), meaning TEXT, word_type VARCHAR(50), jlpt_level VARCHAR(10),
  example_ja TEXT, example_vi TEXT, usage_scope VARCHAR(20) NOT NULL DEFAULT 'TEXTBOOK',
  dictionary_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_test_vocabulary_normalized (normalized_expression, normalized_reading)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_external_reference (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  vocabulary_id BIGINT UNSIGNED NOT NULL, source_id BIGINT UNSIGNED NOT NULL,
  external_entry_id VARCHAR(255) NOT NULL,
  imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_test_external_source (source_id, external_entry_id),
  CONSTRAINT fk_test_external_vocabulary FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
  CONSTRAINT fk_test_external_source FOREIGN KEY (source_id) REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_reading (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, vocabulary_id BIGINT UNSIGNED NOT NULL,
  reading VARCHAR(255) NOT NULL, normalized_reading VARCHAR(255) NOT NULL,
  reading_romaji VARCHAR(255), usage_note VARCHAR(255),
  UNIQUE KEY uk_test_vocabulary_reading (vocabulary_id, normalized_reading),
  CONSTRAINT fk_test_vocabulary_reading FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_variant (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, vocabulary_id BIGINT UNSIGNED NOT NULL,
  variant_expression VARCHAR(255) NOT NULL, normalized_expression VARCHAR(255) NOT NULL,
  variant_type VARCHAR(30) NOT NULL DEFAULT 'OTHER',
  UNIQUE KEY uk_test_vocabulary_variant (vocabulary_id, normalized_expression),
  CONSTRAINT fk_test_vocabulary_variant FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_sense (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, vocabulary_id BIGINT UNSIGNED NOT NULL,
  sense_order SMALLINT UNSIGNED NOT NULL, usage_note VARCHAR(1000),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_test_vocabulary_sense (vocabulary_id, sense_order),
  CONSTRAINT fk_test_vocabulary_sense FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_gloss (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, sense_id BIGINT UNSIGNED NOT NULL,
  language_code VARCHAR(10) NOT NULL, gloss_text VARCHAR(2000) NOT NULL,
  CONSTRAINT fk_test_gloss_sense FOREIGN KEY (sense_id) REFERENCES vocabulary_sense(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dictionary_part_of_speech (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, code VARCHAR(50) NOT NULL UNIQUE,
  name_vi VARCHAR(150) NOT NULL, name_en VARCHAR(150), name_ja VARCHAR(150), description VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dictionary_domain (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, code VARCHAR(80) NOT NULL UNIQUE,
  name_vi VARCHAR(150) NOT NULL, name_en VARCHAR(150), name_ja VARCHAR(150), description VARCHAR(1000),
  is_active TINYINT(1) NOT NULL DEFAULT 1, display_order INT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sense_part_of_speech (
  sense_id BIGINT UNSIGNED NOT NULL, part_of_speech_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (sense_id, part_of_speech_id),
  CONSTRAINT fk_test_sense_pos_sense FOREIGN KEY (sense_id) REFERENCES vocabulary_sense(id) ON DELETE CASCADE,
  CONSTRAINT fk_test_sense_pos_catalog FOREIGN KEY (part_of_speech_id) REFERENCES dictionary_part_of_speech(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sense_domain (
  sense_id BIGINT UNSIGNED NOT NULL, domain_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (sense_id, domain_id),
  CONSTRAINT fk_test_sense_domain_sense FOREIGN KEY (sense_id) REFERENCES vocabulary_sense(id) ON DELETE CASCADE,
  CONSTRAINT fk_test_sense_domain_catalog FOREIGN KEY (domain_id) REFERENCES dictionary_domain(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vocabulary_kanji (
  vocabulary_id BIGINT UNSIGNED NOT NULL, kanji_id BIGINT UNSIGNED NOT NULL,
  character_position SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (vocabulary_id, kanji_id, character_position),
  CONSTRAINT fk_test_vocabulary_kanji_vocabulary FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
  CONSTRAINT fk_test_vocabulary_kanji_kanji FOREIGN KEY (kanji_id) REFERENCES kanji(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS content_item (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS content_vocabulary (
  content_item_id BIGINT UNSIGNED NOT NULL, vocabulary_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (content_item_id, vocabulary_id),
  CONSTRAINT fk_test_content_vocabulary_item FOREIGN KEY (content_item_id) REFERENCES content_item(id) ON DELETE CASCADE,
  CONSTRAINT fk_test_content_vocabulary_word FOREIGN KEY (vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
