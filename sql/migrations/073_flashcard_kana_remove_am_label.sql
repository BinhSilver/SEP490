-- Bo nhan "Am" o truong nghia cua hai bo Hiragana/Katakana co san ("Âm a" -> "a").
-- Seed sinh lai khong cap nhat hai bo nay nen can migration. Chay lai an toan: dong da sua khong con khop LIKE.
-- Chi dung bo goc (source_set_id IS NULL), khong dung ban sao cua nguoi hoc; the Kanji co nghia that nhu "Âm nhạc" khong thuoc pham vi.
SET NAMES utf8mb4;

UPDATE flashcard_card c
    JOIN flashcard_set s ON s.id = c.set_id
SET c.meaning = SUBSTRING(c.meaning, 4)
WHERE s.title IN ('Hiragana cơ bản', 'Katakana cơ bản')
  AND s.source_set_id IS NULL
  AND c.word_type = 'KANA'
  AND c.meaning LIKE 'Âm %' COLLATE utf8mb4_bin;
