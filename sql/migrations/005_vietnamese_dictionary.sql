-- JLAS2-34: chuyen tu dien Nhat-Anh (JMdict) sang Nhat-Viet.
-- Apply sau 004_dictionary_import_metadata.sql. Chi them/doi thuoc tinh cot, khong xoa du lieu.

-- 1. Am Han Viet cua ca tu (vi du 学校 -> HOC HIEU). Kanji da co kanji.han_viet,
--    tu vung chua co cot tuong ung.
ALTER TABLE vocabulary_entry
    ADD COLUMN han_viet VARCHAR(255) NULL AFTER reading_romaji,
    ADD KEY idx_vocabulary_han_viet (han_viet);

-- 2. Khoa tra cuu tieng Nhat so khop dung byte. utf8mb4_unicode_ci coi は/ば/ぱ,
--    つ/っ la bang nhau nen unique key gop nham cac tu khac nhau (かき/かぎ) va cac
--    query "COLLATE utf8mb4_bin" phia Java khong dung duoc index.
ALTER TABLE vocabulary_entry
    MODIFY normalized_expression VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY normalized_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE vocabulary_reading
    MODIFY normalized_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE vocabulary_variant
    MODIFY normalized_expression VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- unicode_ci gan cung mot trong so cho moi ky tu ngoai BMP (U+20000...), nen uk_kanji_character
-- chan 300+ Kanji hiem cua KANJIDIC2 day du.
ALTER TABLE kanji
    MODIFY `character` VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- Tuong tu, uk_kanji_reading lam mat am ハク/バク, フン/ブン cua cung mot Kanji (博, 分).
ALTER TABLE kanji_reading
    MODIFY reading_text VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

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
