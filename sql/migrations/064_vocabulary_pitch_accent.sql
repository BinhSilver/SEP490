-- Pitch notation is imported only from the two attributed source dictionaries.
INSERT INTO dictionary_source (code, name, source_url, license_name, license_url, is_active)
VALUES
    ('UNIDIC_CWJ', '現代書き言葉UniDic 2025.12', 'https://clrd.ninjal.ac.jp/unidic/',
     'GPL-2.0 / LGPL-2.1 / BSD-3-Clause', NULL, 1),
    ('KANJIUM', 'Kanjium', 'https://github.com/mifunetoshiro/kanjium',
     'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/', 1)
ON DUPLICATE KEY UPDATE code = VALUES(code);

CREATE TABLE IF NOT EXISTS vocabulary_pitch_accent (
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    accents       VARCHAR(50) NOT NULL,
    source_id     BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (vocabulary_id),
    KEY idx_vocabulary_pitch_accent_source (source_id),
    CONSTRAINT fk_vocabulary_pitch_accent_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT fk_vocabulary_pitch_accent_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
