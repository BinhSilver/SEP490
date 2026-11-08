-- JLAS2-34: chuyen tu dien Nhat-Anh (JMdict) sang Nhat-Viet.
-- Apply sau 058_dictionary_import_metadata.sql. Chi them/doi thuoc tinh cot, khong xoa du lieu.

-- 1. Am Han Viet cua ca tu (vi du 学校 -> HOC HIEU). Kanji da co kanji.han_viet,
--    tu vung chua co cot tuong ung.
ALTER TABLE vocabulary_entry
    ADD COLUMN han_viet VARCHAR(255) NULL AFTER reading_romaji,
    ADD KEY idx_vocabulary_han_viet (han_viet);

-- 2. Khoa tra cuu tieng Nhat dung utf8mb4_0900_as_cs (accent- and case-sensitive).
--    utf8mb4_unicode_ci coi ha/ba/pa (は/ば/ぱ) va かき/かぎ la bang nhau;
--    utf8mb4_0900_as_cs giu chung khac nhau, khop schema develop va cho seed develop
--    dung COLLATE utf8mb4_0900_as_cs ma van dung index.
ALTER TABLE vocabulary_entry
    MODIFY normalized_expression VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    MODIFY normalized_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

ALTER TABLE vocabulary_reading
    MODIFY normalized_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

ALTER TABLE vocabulary_variant
    MODIFY normalized_expression VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

-- unicode_ci gan cung mot trong so cho moi ky tu ngoai BMP (U+20000...), nen uk_kanji_character
-- chan 300+ Kanji hiem cua KANJIDIC2 day du.
ALTER TABLE kanji
    MODIFY `character` VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- Tuong tu, kanji_reading.reading_text dung utf8mb4_0900_as_cs (accent- and
-- case-sensitive) de giu rieng ハク/バク va フン/ブン; khop schema develop
-- nen lookup dung COLLATE utf8mb4_0900_as_cs van dung index.
ALTER TABLE kanji_reading
    MODIFY reading_text VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

-- 3. Nguon du lieu Nhat-Viet.
INSERT IGNORE INTO dictionary_source
    (code, name, source_url, license_name, license_url, is_active)
VALUES
    ('MAZII_VI', 'Tu dien Nhat - Viet (Mazii, ban Yomitan)',
     'https://github.com/Buiduyanh20/yomitan-japanese-vietnamese-dictionary',
     NULL, NULL, 1),
    ('KANJIDICT_VN', 'Tu dien Han Nom (KanjiDictVN)',
     'https://github.com/trungnt2910/KanjiDictVN',
     NULL, NULL, 1);

-- Manual rollback (never run by the importer):
-- DELETE FROM dictionary_source WHERE code IN ('MAZII_VI', 'KANJIDICT_VN');
-- ALTER TABLE kanji_reading MODIFY reading_text VARCHAR(100) NOT NULL;
-- ALTER TABLE kanji MODIFY `character` VARCHAR(8) NOT NULL;
-- ALTER TABLE vocabulary_variant MODIFY normalized_expression VARCHAR(255) NOT NULL;
-- ALTER TABLE vocabulary_reading MODIFY normalized_reading VARCHAR(255) NOT NULL;
-- ALTER TABLE vocabulary_entry MODIFY normalized_expression VARCHAR(255) NOT NULL,
--     MODIFY normalized_reading VARCHAR(255) NOT NULL;
-- ALTER TABLE vocabulary_entry DROP KEY idx_vocabulary_han_viet, DROP COLUMN han_viet;
