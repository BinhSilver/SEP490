-- KotsuKotsu / Lộ trình học — 3 lệnh chạy trên DB server (chạy lại nhiều lần vẫn an toàn). 11/11/2026
-- 1) Sửa tên + mô tả sách học liệu của Lộ trình (hết hiện JSON/link nguồn)
-- [JLAS-W0108] 09/11: lesson/textbook pages showed internal JSON (source link, codes) and original book names.
-- Plain titles and descriptions for the learning-path supplementary books; lesson descriptions cleared.
UPDATE textbook SET
  title = CASE
    WHEN source_code LIKE 'LP-REVIEWED-%' THEN CONCAT('Học liệu bổ sung ', jlpt_level, ' (bản ôn)')
    WHEN source_code = 'LP-OWNED-NGU-PHAP-N2' THEN 'Học liệu bổ sung N2 — Ngữ pháp'
    ELSE CONCAT('Học liệu bổ sung ', jlpt_level) END,
  description = CONCAT('Học liệu bổ sung của Lộ trình học, cấp ', jlpt_level, ': từ vựng, Kanji và bài đọc theo chủ đề.')
WHERE source_code REGEXP '^LP-(OWNED|REVIEWED)-(SPEED|SHINKANZEN-MASTER|NGU-PHAP)-N[1-5]$';

UPDATE lesson l
  JOIN unit u ON u.id = l.unit_id
  JOIN textbook b ON b.id = u.textbook_id
SET l.description = NULL
WHERE b.source_code LIKE 'LP-%' AND l.description LIKE '{%';

-- 2) Bỏ cờ lộ trình khỏi sách của Giáo trình (lộ trình chỉ đọc sách LP-OWNED-/LP-REVIEWED- của mình)
-- [JLAS-W0108] The learning paths read only their own LP-OWNED-/LP-REVIEWED- books; the textbook team's books
-- are no longer a learning-path source. Safe to run more than once.
UPDATE textbook SET is_learning_path_source = 0
WHERE is_learning_path_source = 1
  AND (source_code IS NULL OR source_code NOT REGEXP '^LP-(OWNED|REVIEWED)-');

-- 3) Index cho trang lộ trình (158 ms -> 30 ms)
-- [JLAS-W0108] 09/11 F10: count indexes for GET /api/learning/jlpt/path (SQL 158 ms -> 30 ms on N5). Re-runnable.
SET @jpath_ddl = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_jpath_gq_level_bank ON jpath_grammar_question (level, bank, status, report_hidden, grammar_code)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_grammar_question' AND INDEX_NAME = 'idx_jpath_gq_level_bank');
PREPARE jpath_ddl_stmt FROM @jpath_ddl;
EXECUTE jpath_ddl_stmt;
DEALLOCATE PREPARE jpath_ddl_stmt;
SET @jpath_ddl = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_jpath_vocab_count ON jpath_vocabulary (level, status, report_hidden)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_vocabulary' AND INDEX_NAME = 'idx_jpath_vocab_count');
PREPARE jpath_ddl_stmt FROM @jpath_ddl;
EXECUTE jpath_ddl_stmt;
DEALLOCATE PREPARE jpath_ddl_stmt;
SET @jpath_ddl = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_jpath_kanji_count ON jpath_kanji (level, status, report_hidden)', 'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_kanji' AND INDEX_NAME = 'idx_jpath_kanji_count');
PREPARE jpath_ddl_stmt FROM @jpath_ddl;
EXECUTE jpath_ddl_stmt;
DEALLOCATE PREPARE jpath_ddl_stmt;

