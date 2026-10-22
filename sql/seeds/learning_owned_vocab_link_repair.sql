-- [JLAS-W0108] OD-12 (owner 2026-10-21): seven Sekai vocabulary links were lost when vocabulary_entry 87 (今日) and 633
-- (食べる) were removed in the 17/10 reseed; 9097/9098 are the replacement entries. The owner approved adding them to
-- the Sekai books (tb 1, tb 2) this once, plus the Learning-owned N4 copy (tb 4000002). Additive and re-runnable: each
-- row is inserted only while 87/633 are gone, the replacement matches by word and reading, the content item sits in the
-- expected book, and the link is not there yet. On a fresh install (87/633 present, 9097/9098 absent) it does nothing.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO content_vocabulary (content_item_id, vocabulary_id)
SELECT ci.id, v.id
FROM (SELECT 72 AS item_id, 1 AS book_id, 9097 AS vocab_id, 87 AS lost_id
      UNION ALL SELECT 1057, 1, 9097, 87
      UNION ALL SELECT 1262, 1, 9097, 87
      UNION ALL SELECT 1127, 1, 9098, 633
      UNION ALL SELECT 1262, 1, 9098, 633
      UNION ALL SELECT 2473, 2, 9098, 633
      UNION ALL SELECT 4002473, 4000002, 9098, 633) AS repair
JOIN content_item ci ON ci.id = repair.item_id
JOIN lesson_section s ON s.id = ci.section_id
JOIN lesson l ON l.id = s.lesson_id
JOIN unit u ON u.id = l.unit_id AND u.textbook_id = repair.book_id
JOIN vocabulary_entry v ON v.id = repair.vocab_id
WHERE ((repair.vocab_id = 9097 AND BINARY v.expression = BINARY '今日' AND BINARY v.reading = BINARY 'きょう')
    OR (repair.vocab_id = 9098 AND BINARY v.expression = BINARY '食べる' AND BINARY v.reading = BINARY 'たべる'))
  AND NOT EXISTS (SELECT 1 FROM vocabulary_entry lost WHERE lost.id = repair.lost_id)
  AND NOT EXISTS (SELECT 1 FROM content_vocabulary cv WHERE cv.content_item_id = ci.id AND cv.vocabulary_id = v.id);
COMMIT;
