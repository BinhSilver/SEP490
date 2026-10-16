-- JLAS2-34: tra Kanji theo bo thu/bo phan (multi-radical lookup, du lieu RADKFILE cua EDRDG).
-- Apply sau 005_vietnamese_dictionary.sql. Chi them bang moi.

-- Bo phan dung de tra cuu (253 bo phan cua RADKFILE). `character` la glyph hien thi (vd. 亻),
-- source_character la ky tu RADKFILE dung thay khi glyph khong co trong JIS (vd. 化 cho 亻).
CREATE TABLE IF NOT EXISTS kanji_component (
    id               SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `character`      VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_character VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    stroke_count     TINYINT UNSIGNED NOT NULL,
    radical_id       SMALLINT UNSIGNED NULL,       -- Bo thu Khang Hi tuong ung (lay ten tieng Viet).
    display_order    SMALLINT UNSIGNED NOT NULL,   -- Thu tu trong RADKFILE (theo so net).
    PRIMARY KEY (id),
    UNIQUE KEY uk_kanji_component_character (`character`),
    UNIQUE KEY uk_kanji_component_source (source_character),
    KEY idx_kanji_component_order (stroke_count, display_order),
    CONSTRAINT fk_kanji_component_radical FOREIGN KEY (radical_id)
        REFERENCES radical(id) ON DELETE SET NULL,
    CONSTRAINT chk_kanji_component_strokes CHECK (stroke_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Kanji chua bo phan nao. Tra nhieu bo phan = giao cac tap kanji_id.
CREATE TABLE IF NOT EXISTS kanji_component_map (
    kanji_id     BIGINT UNSIGNED NOT NULL,
    component_id SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (kanji_id, component_id),
    KEY idx_kanji_component_map_component (component_id, kanji_id),
    CONSTRAINT fk_kanji_component_map_kanji FOREIGN KEY (kanji_id)
        REFERENCES kanji(id) ON DELETE CASCADE,
    CONSTRAINT fk_kanji_component_map_component FOREIGN KEY (component_id)
        REFERENCES kanji_component(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO dictionary_source
    (code, name, source_url, license_name, license_url, is_active)
VALUES
    ('RADKFILE', 'RADKFILE/KRADFILE', 'https://www.edrdg.org/krad/kradinf.html',
     'Creative Commons Attribution-ShareAlike 4.0',
     'https://www.edrdg.org/edrdg/licence.html', 1);

-- Manual rollback (never run by the importer):
-- DELETE FROM dictionary_source WHERE code = 'RADKFILE';
-- DROP TABLE kanji_component_map;
-- DROP TABLE kanji_component;
