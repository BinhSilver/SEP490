-- 086) Sửa cách đọc từ vựng của bài đang chứa chữ Hán (vd 住みます đọc "住みます") + romaji cho các từ vừa sửa.
-- SINH TỰ ĐỘNG bởi tools/gen_underline_romaji_migration.py --reading-fix — không sửa tay.
-- Chỉ sửa dữ liệu, không đổi schema. Chạy SAU seed (vnjpclub, Minna) và SAU 085.
-- Nguồn cách đọc: a) cùng mặt chữ ở mục nội dung khác / thẻ flashcard, mọi cách đọc giống nhau;
--                 b) JMdict trong DB, khớp chính xác mặt chữ và chỉ có 1 cách đọc. Còn lại: sql/audits/reading_review.csv.
-- Chạy lại an toàn: tìm từ theo khoá UNIQUE (normalized_expression, normalized_reading) — KHÔNG theo id (id từ vựng
-- khác nhau giữa các DB) — chỉ khi cách đọc hiện tại đúng bằng bản gốc và không đụng UNIQUE; romaji chỉ ghi khi
-- còn rỗng và cách đọc đã là bản mới. Chuỗi tiếng Nhật ghi dạng hex.
-- Cách đọc: 1 (a: 0, b: 1). Romaji: 1.
SET NAMES utf8mb4;

START TRANSACTION;

-- 1) Cách đọc (cập nhật kèm normalized_reading theo quy tắc katakana -> hiragana(NFKC))
-- [b] パン屋: パン屋 -> パンや
UPDATE vocabulary_entry SET reading = CONVERT(X'E38391E383B3E38284' USING utf8mb4), normalized_reading = CONVERT(X'E381B1E38293E38284' USING utf8mb4)
WHERE normalized_expression = CONVERT(X'E381B1E38293E5B18B' USING utf8mb4) COLLATE utf8mb4_0900_as_cs AND normalized_reading = CONVERT(X'E381B1E38293E5B18B' USING utf8mb4) COLLATE utf8mb4_0900_as_cs AND reading COLLATE utf8mb4_bin = CONVERT(X'E38391E383B3E5B18B' USING utf8mb4)
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM vocabulary_entry WHERE normalized_expression = CONVERT(X'E381B1E38293E5B18B' USING utf8mb4) COLLATE utf8mb4_0900_as_cs
                                 AND normalized_reading = CONVERT(X'E381B1E38293E38284' USING utf8mb4) COLLATE utf8mb4_0900_as_cs LIMIT 1) d);

-- 2) Romaji cho từ vừa có cách đọc kana (cùng hàm/style với 085)
DROP TEMPORARY TABLE IF EXISTS tmp_vocab_romaji;
CREATE TEMPORARY TABLE tmp_vocab_romaji (
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    romaji VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    PRIMARY KEY (ne, nr)
) ENGINE=InnoDB;
INSERT INTO tmp_vocab_romaji (ne, nr, reading, romaji) VALUES
    (CONVERT(X'E381B1E38293E5B18B' USING utf8mb4), CONVERT(X'E381B1E38293E38284' USING utf8mb4), CONVERT(X'E38391E383B3E38284' USING utf8mb4), 'panya');
UPDATE vocabulary_entry v
    JOIN tmp_vocab_romaji t ON v.normalized_expression = t.ne AND v.normalized_reading = t.nr
SET v.reading_romaji = t.romaji
WHERE (v.reading_romaji IS NULL OR v.reading_romaji = '') AND v.reading COLLATE utf8mb4_bin = t.reading;
DROP TEMPORARY TABLE IF EXISTS tmp_vocab_romaji;

COMMIT;
