-- [JLAS2-270] Consolidated feature update. Select the intended database before running.
-- Prepared for review only; no SQL is executed by the commit workflow.

-- ===== 040_jlpt_pdf_visual_repair.sql =====
-- Restore N1/N2 source-PDF passages and illustrated listening questions.
-- Apply after the complete JLPT seeds. Safe to rerun on an existing database.
-- Source: the PDF papers in E:/JLPTFunc/data (pages recorded in image filenames/audit).
-- Keep the matching PNG files under src/main/resources/static/images/jlpt.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @jlpt_pdf_old_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
START TRANSACTION;

-- N1_2013_07, Mondai 6, câu 69-70.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9001, '<img src="/images/jlpt/N1/N1_2013_07/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2013_07, câu 69-70, trang 1" loading="lazy">', 9901
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2013_07' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9001);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N1/N1_2013_07/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2013_07, câu 69-70, trang 1" loading="lazy">'
WHERE e.exam_code='N1_2013_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=9001;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9001
SET q.passage_id=ps.id
WHERE e.exam_code='N1_2013_07' AND p.part_code='grammar_reading'
  AND m.mondai_number=6 AND q.question_number IN (69,70) AND q.passage_id IS NULL;

-- N1_2014_07, Mondai 6, câu 69-70.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9002, '<img src="/images/jlpt/N1/N1_2014_07/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2014_07, câu 69-70, trang 1" loading="lazy"><img src="/images/jlpt/N1/N1_2014_07/Mondai6/passage_69-70_2.png" alt="Đoạn đọc JLPT N1_2014_07, câu 69-70, trang 2" loading="lazy">', 9902
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2014_07' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9002);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N1/N1_2014_07/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2014_07, câu 69-70, trang 1" loading="lazy"><img src="/images/jlpt/N1/N1_2014_07/Mondai6/passage_69-70_2.png" alt="Đoạn đọc JLPT N1_2014_07, câu 69-70, trang 2" loading="lazy">'
WHERE e.exam_code='N1_2014_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=9002;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9002
SET q.passage_id=ps.id
WHERE e.exam_code='N1_2014_07' AND p.part_code='grammar_reading'
  AND m.mondai_number=6 AND q.question_number IN (69,70) AND q.passage_id IS NULL;

-- N1_2016_12, Mondai 6, câu 69-70.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9003, '<img src="/images/jlpt/N1/N1_2016_12/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2016_12, câu 69-70, trang 1" loading="lazy"><img src="/images/jlpt/N1/N1_2016_12/Mondai6/passage_69-70_2.png" alt="Đoạn đọc JLPT N1_2016_12, câu 69-70, trang 2" loading="lazy">', 9903
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2016_12' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9003);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N1/N1_2016_12/Mondai6/passage_69-70_1.png" alt="Đoạn đọc JLPT N1_2016_12, câu 69-70, trang 1" loading="lazy"><img src="/images/jlpt/N1/N1_2016_12/Mondai6/passage_69-70_2.png" alt="Đoạn đọc JLPT N1_2016_12, câu 69-70, trang 2" loading="lazy">'
WHERE e.exam_code='N1_2016_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=9003;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9003
SET q.passage_id=ps.id
WHERE e.exam_code='N1_2016_12' AND p.part_code='grammar_reading'
  AND m.mondai_number=6 AND q.question_number IN (69,70) AND q.passage_id IS NULL;

-- N1_2019_07, Mondai 6, câu 68-69.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9004, '<img src="/images/jlpt/N1/N1_2019_07/Mondai6/passage_68-69_1.png" alt="Đoạn đọc JLPT N1_2019_07, câu 68-69, trang 1" loading="lazy">', 9904
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2019_07' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9004);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N1/N1_2019_07/Mondai6/passage_68-69_1.png" alt="Đoạn đọc JLPT N1_2019_07, câu 68-69, trang 1" loading="lazy">'
WHERE e.exam_code='N1_2019_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=9004;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9004
SET q.passage_id=ps.id
WHERE e.exam_code='N1_2019_07' AND p.part_code='grammar_reading'
  AND m.mondai_number=6 AND q.question_number IN (68,69) AND q.passage_id IS NULL;

-- N2_2011_07, Mondai 3, câu 69-70.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2011_07/Mondai3/passage_69-70_1.png" alt="Đoạn đọc JLPT N2_2011_07, câu 69-70, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2011_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=876;

-- N2_2011_07, Mondai 5, câu 74-75.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2011_07/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2011_07, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2011_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=878;

-- N2_2011_12, Mondai 5, câu 74-75.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2011_12/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2011_12, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2011_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=889;

-- N2_2012_12, Mondai 5, câu 74-75.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2012_12/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2012_12, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2012_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=911;

-- N2_2013_07, Mondai 5, câu 74-75.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2013_07/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2013_07, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2013_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=922;

-- N2_2013_12, Mondai 5, câu 74-75.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9005, '<img src="/images/jlpt/N2/N2_2013_12/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2013_12, câu 74-75, trang 1" loading="lazy">', 9905
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2013_12' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9005);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2013_12/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2013_12, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2013_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=9005;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9005
SET q.passage_id=ps.id
WHERE e.exam_code='N2_2013_12' AND p.part_code='grammar_reading'
  AND m.mondai_number=5 AND q.question_number IN (74,75) AND q.passage_id IS NULL;

-- N2_2014_07, Mondai 5, câu 74-75.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9006, '<img src="/images/jlpt/N2/N2_2014_07/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2014_07, câu 74-75, trang 1" loading="lazy">', 9906
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9006);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2014_07/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2014_07, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2014_07' AND p.part_code='grammar_reading' AND ps.source_passage_id=9006;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9006
SET q.passage_id=ps.id
WHERE e.exam_code='N2_2014_07' AND p.part_code='grammar_reading'
  AND m.mondai_number=5 AND q.question_number IN (74,75) AND q.passage_id IS NULL;

-- N2_2014_12, Mondai 5, câu 74-75.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2014_12/Mondai5/passage_74-75_1.png" alt="Đoạn đọc JLPT N2_2014_12, câu 74-75, trang 1" loading="lazy">'
WHERE e.exam_code='N2_2014_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=953;

-- N2_2018_12, Mondai 5, câu 72-73.
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2018_12/Mondai5/passage_72-73_1.png" alt="Đoạn đọc JLPT N2_2018_12, câu 72-73, trang 1" loading="lazy"><img src="/images/jlpt/N2/N2_2018_12/Mondai5/passage_72-73_2.png" alt="Đoạn đọc JLPT N2_2018_12, câu 72-73, trang 2" loading="lazy">'
WHERE e.exam_code='N2_2018_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=1039;

-- N2_2020_12, Mondai 5, câu 71-72.
INSERT INTO jlpt_passage(part_id, source_passage_id, passage_html, order_index)
SELECT p.id, 9007, '<img src="/images/jlpt/N2/N2_2020_12/Mondai5/passage_71-72_1.png" alt="Đoạn đọc JLPT N2_2020_12, câu 71-72, trang 1" loading="lazy"><img src="/images/jlpt/N2/N2_2020_12/Mondai5/passage_71-72_2.png" alt="Đoạn đọc JLPT N2_2020_12, câu 71-72, trang 2" loading="lazy">', 9907
FROM jlpt_part p JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2020_12' AND p.part_code='grammar_reading'
  AND NOT EXISTS (SELECT 1 FROM jlpt_passage x WHERE x.part_id=p.id AND x.source_passage_id=9007);
UPDATE jlpt_passage ps JOIN jlpt_part p ON p.id=ps.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET ps.passage_html='<img src="/images/jlpt/N2/N2_2020_12/Mondai5/passage_71-72_1.png" alt="Đoạn đọc JLPT N2_2020_12, câu 71-72, trang 1" loading="lazy"><img src="/images/jlpt/N2/N2_2020_12/Mondai5/passage_71-72_2.png" alt="Đoạn đọc JLPT N2_2020_12, câu 71-72, trang 2" loading="lazy">'
WHERE e.exam_code='N2_2020_12' AND p.part_code='grammar_reading' AND ps.source_passage_id=9007;
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_passage ps ON ps.part_id=p.id AND ps.source_passage_id=9007
SET q.passage_id=ps.id
WHERE e.exam_code='N2_2020_12' AND p.part_code='grammar_reading'
  AND m.mondai_number=5 AND q.question_number IN (71,72) AND q.passage_id IS NULL;

-- N1_2018_12, listening Mondai 1, question 1; answer confirmed from its script.
INSERT INTO jlpt_question(mondai_id, question_type_id, question_number,
  question_text, question_html, correct_option, score, image_url, source_page, order_index)
SELECT m.id, qt.id, 1, '1番', '1番', 4, 2.00, '/images/jlpt/N1/N1_2018_12/Mondai1/1.png', 22, 1
FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_question_type qt ON qt.type_code='LISTENING_TASK_BASED'
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_question q WHERE q.mondai_id=m.id AND q.question_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 1, '1', '1', 1 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 2, '2', '2', 2 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=2);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 3, '3', '3', 3 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=3);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 4, '4', '4', 4 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=4);
INSERT INTO jlpt_audio_clip(mondai_id, question_id, clip_type, audio_url, order_index)
SELECT m.id, q.id, 'QUESTION', '/audio/JLPT/N1/N1_2018_12/Mondai1/1.mp3', 1
FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_audio_clip c WHERE c.question_id=q.id);
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.order_index=q.question_number
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1;
UPDATE jlpt_audio_clip c JOIN jlpt_question q ON q.id=c.question_id
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id SET c.order_index=q.question_number
WHERE e.exam_code='N1_2018_12' AND p.part_code='listening' AND m.mondai_number=1;

-- N2_2012_12, listening Mondai 1, question 4; answer confirmed from its script.
INSERT INTO jlpt_question(mondai_id, question_type_id, question_number,
  question_text, question_html, correct_option, score, image_url, source_page, order_index)
SELECT m.id, qt.id, 4, '4番', '4番', 3, 2.00, '/images/jlpt/N2/N2_2012_12/Mondai1/4.png', 21, 4
FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_question_type qt ON qt.type_code='LISTENING_TASK_BASED'
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_question q WHERE q.mondai_id=m.id AND q.question_number=4);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 1, '1', '1', 1 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 2, '2', '2', 2 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=2);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 3, '3', '3', 3 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=3);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 4, '4', '4', 4 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=4);
INSERT INTO jlpt_audio_clip(mondai_id, question_id, clip_type, audio_url, order_index)
SELECT m.id, q.id, 'QUESTION', '/audio/JLPT/N2/N2_2012_12/Mondai1/4.mp3', 4
FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_audio_clip c WHERE c.question_id=q.id);
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.order_index=q.question_number
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1;
UPDATE jlpt_audio_clip c JOIN jlpt_question q ON q.id=c.question_id
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id SET c.order_index=q.question_number
WHERE e.exam_code='N2_2012_12' AND p.part_code='listening' AND m.mondai_number=1;

-- N2_2014_07, listening Mondai 1, question 4; answer confirmed from its script.
INSERT INTO jlpt_question(mondai_id, question_type_id, question_number,
  question_text, question_html, correct_option, score, image_url, source_page, order_index)
SELECT m.id, qt.id, 4, '4番', '4番', 3, 2.00, '/images/jlpt/N2/N2_2014_07/Mondai1/4.png', 18, 4
FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_question_type qt ON qt.type_code='LISTENING_TASK_BASED'
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_question q WHERE q.mondai_id=m.id AND q.question_number=4);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 1, '1', '1', 1 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 2, '2', '2', 2 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=2);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 3, '3', '3', 3 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=3);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 4, '4', '4', 4 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=4);
INSERT INTO jlpt_audio_clip(mondai_id, question_id, clip_type, audio_url, order_index)
SELECT m.id, q.id, 'QUESTION', '/audio/JLPT/N2/N2_2014_07/Mondai1/4.mp3', 4
FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=4
  AND NOT EXISTS (SELECT 1 FROM jlpt_audio_clip c WHERE c.question_id=q.id);
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.order_index=q.question_number
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1;
UPDATE jlpt_audio_clip c JOIN jlpt_question q ON q.id=c.question_id
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id SET c.order_index=q.question_number
WHERE e.exam_code='N2_2014_07' AND p.part_code='listening' AND m.mondai_number=1;

-- N2_2018_12, listening Mondai 1, question 1; answer confirmed from its script.
INSERT INTO jlpt_question(mondai_id, question_type_id, question_number,
  question_text, question_html, correct_option, score, image_url, source_page, order_index)
SELECT m.id, qt.id, 1, '1番', '1番', 4, 2.00, '/images/jlpt/N2/N2_2018_12/Mondai1/1.png', 19, 1
FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
JOIN jlpt_question_type qt ON qt.type_code='LISTENING_TASK_BASED'
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_question q WHERE q.mondai_id=m.id AND q.question_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 1, '1', '1', 1 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=1);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 2, '2', '2', 2 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=2);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 3, '3', '3', 3 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=3);
INSERT INTO jlpt_option(question_id, option_number, option_text, option_html, order_index)
SELECT q.id, 4, '4', '4', 4 FROM jlpt_question q
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_option o WHERE o.question_id=q.id AND o.option_number=4);
INSERT INTO jlpt_audio_clip(mondai_id, question_id, clip_type, audio_url, order_index)
SELECT m.id, q.id, 'QUESTION', '/audio/JLPT/N2/N2_2018_12/Mondai1/1.mp3', 1
FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1
  AND q.question_number=1
  AND NOT EXISTS (SELECT 1 FROM jlpt_audio_clip c WHERE c.question_id=q.id);
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.order_index=q.question_number
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1;
UPDATE jlpt_audio_clip c JOIN jlpt_question q ON q.id=c.question_id
JOIN jlpt_mondai m ON m.id=q.mondai_id JOIN jlpt_part p ON p.id=m.part_id
JOIN jlpt_exam e ON e.id=p.exam_id SET c.order_index=q.question_number
WHERE e.exam_code='N2_2018_12' AND p.part_code='listening' AND m.mondai_number=1;

-- Use already archived local copies for this existing listening diagram.
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.image_url='/images/jlpt/N1/N1_2017_12/Mondai1/2.png'
WHERE e.exam_code='N1_2017_12' AND p.part_code='listening' AND m.mondai_number=1 AND q.question_number=2;

-- Use already archived local copies for this existing listening diagram.
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.image_url='/images/jlpt/N2/N2_2011_12/Mondai1/2.png'
WHERE e.exam_code='N2_2011_12' AND p.part_code='listening' AND m.mondai_number=1 AND q.question_number=2;

-- Use already archived local copies for this existing listening diagram.
UPDATE jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
SET q.image_url='/images/jlpt/N2/N2_2012_07/Mondai1/3.png'
WHERE e.exam_code='N2_2012_07' AND p.part_code='listening' AND m.mondai_number=1 AND q.question_number=3;

COMMIT;
SET SQL_SAFE_UPDATES = @jlpt_pdf_old_safe_updates;

-- ===== 041_jlpt_remove_source_and_audio_offsets.sql =====
-- [JLAS2-270] Align an existing JLPT database with the standalone-audio schema.
-- Run after the legacy JLPT full setup/seeds and before any seed written for this schema.
-- Safe to rerun. DDL commits implicitly in MySQL.

SET @jlpt_schema = DATABASE();

SELECT IF(COUNT(*) > 0,
    'ALTER TABLE jlpt_question DROP INDEX uk_jlpt_question_source', 'SELECT 1') INTO @ddl
FROM information_schema.statistics
WHERE table_schema=@jlpt_schema AND table_name='jlpt_question'
  AND index_name='uk_jlpt_question_source';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) = 0,
    'ALTER TABLE jlpt_transcript_segment ADD INDEX idx_jlpt_transcript_mondai_order (mondai_id, order_index)',
    'SELECT 1') INTO @ddl
FROM information_schema.statistics
WHERE table_schema=@jlpt_schema AND table_name='jlpt_transcript_segment'
  AND index_name='idx_jlpt_transcript_mondai_order';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0,
    'ALTER TABLE jlpt_transcript_segment DROP INDEX idx_jlpt_transcript_time', 'SELECT 1') INTO @ddl
FROM information_schema.statistics
WHERE table_schema=@jlpt_schema AND table_name='jlpt_transcript_segment'
  AND index_name='idx_jlpt_transcript_time';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_exam DROP COLUMN source_name', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_exam' AND column_name='source_name';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_question DROP COLUMN source_question_id', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_question' AND column_name='source_question_id';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_audio_clip DROP COLUMN start_seconds', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_audio_clip' AND column_name='start_seconds';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_audio_clip DROP COLUMN end_seconds', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_audio_clip' AND column_name='end_seconds';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_transcript_segment DROP COLUMN start_seconds', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_transcript_segment' AND column_name='start_seconds';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

SELECT IF(COUNT(*) > 0, 'ALTER TABLE jlpt_transcript_segment DROP COLUMN end_seconds', 'SELECT 1') INTO @ddl
FROM information_schema.columns
WHERE table_schema=@jlpt_schema AND table_name='jlpt_transcript_segment' AND column_name='end_seconds';
PREPARE jlpt_stmt FROM @ddl; EXECUTE jlpt_stmt; DEALLOCATE PREPARE jlpt_stmt;

-- ===== 042_jlpt_target_vocabulary.sql =====
USE jlas;
SET NAMES utf8mb4;

SET @jlpt_schema = DATABASE();
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD COLUMN target_vocabulary_id BIGINT UNSIGNED NULL AFTER explanation',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema=@jlpt_schema AND table_name='jlpt_question' AND column_name='target_vocabulary_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD INDEX idx_jlpt_question_target_vocabulary (target_vocabulary_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema=@jlpt_schema AND table_name='jlpt_question' AND index_name='idx_jlpt_question_target_vocabulary'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE jlpt_question ADD CONSTRAINT fk_jlpt_question_target_vocabulary FOREIGN KEY (target_vocabulary_id) REFERENCES vocabulary_entry(id) ON DELETE SET NULL',
        'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema=@jlpt_schema AND table_name='jlpt_question' AND constraint_name='fk_jlpt_question_target_vocabulary'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ===== jlpt_goi_missing_vocabulary.sql =====
USE jlas;
SET NAMES utf8mb4;

-- Goi words found in N3_2024_12 that were absent from vocabulary_entry.
-- Existing dictionary rows are preserved.
INSERT IGNORE INTO vocabulary_entry
    (expression, normalized_expression, reading, normalized_reading,
     meaning, jlpt_level, usage_scope, dictionary_status)
VALUES
    ('深い', '深い', 'ふかい', 'ふかい', 'sâu', 'N4', 'DICTIONARY', 'PUBLISHED'),
    ('感情的', '感情的', 'かんじょうてき', 'かんじょうてき', 'cảm tính; dễ xúc động', 'N3', 'DICTIONARY', 'PUBLISHED'),
    ('勤める', '勤める', 'つとめる', 'つとめる', 'làm việc, công tác tại một tổ chức', 'N5', 'DICTIONARY', 'PUBLISHED');

-- ===== 043_jlpt_goi_target_backfill.sql =====
USE jlas;
SET NAMES utf8mb4;

-- Link only a clearly identified Goi term to an existing dictionary entry.
-- Grammar questions stored in a vocabulary part and word-formation fragments are excluded.
DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_term;
CREATE TEMPORARY TABLE tmp_jlpt_goi_term AS
SELECT q.id AS question_id, NULLIF(TRIM(CASE
    WHEN m.format_code IN ('kanji_reading', 'paraphrase') THEN
        REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
            REGEXP_SUBSTR(q.question_html, '<u>.*?</u>'),
            '<rt>.*?</rt>', ''), '<rp>.*?</rp>', ''), '<[^>]+>', '')
    WHEN m.format_code = 'usage' THEN
        REGEXP_REPLACE(q.question_text, '^[0-9]+[.．][[:space:]]*', '')
    WHEN m.format_code IN ('orthography', 'context') THEN o.option_text
    ELSE NULL
END), '') AS expression
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
LEFT JOIN jlpt_option o ON o.question_id = q.id AND o.option_number = q.correct_option
WHERE p.part_code = 'vocabulary'
  AND m.format_code IN ('kanji_reading', 'orthography', 'context', 'paraphrase', 'usage')
  AND q.target_vocabulary_id IS NULL;

ALTER TABLE tmp_jlpt_goi_term ADD PRIMARY KEY (question_id);

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_unique;
CREATE TEMPORARY TABLE tmp_jlpt_goi_unique AS
SELECT t.question_id, MIN(v.id) AS vocabulary_id
FROM tmp_jlpt_goi_term t
JOIN vocabulary_entry v
  ON v.expression COLLATE utf8mb4_0900_as_cs = t.expression COLLATE utf8mb4_0900_as_cs
WHERE t.expression IS NOT NULL
GROUP BY t.question_id
HAVING COUNT(v.id) = 1;

ALTER TABLE tmp_jlpt_goi_unique ADD PRIMARY KEY (question_id);

SET @previous_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
START TRANSACTION;

UPDATE jlpt_question q
JOIN tmp_jlpt_goi_unique u ON u.question_id = q.id
SET q.target_vocabulary_id = u.vocabulary_id
WHERE q.target_vocabulary_id IS NULL;

-- Existing saved attempts also gain their missed Goi words without retaking an exam.
INSERT IGNORE INTO user_notebook(user_id, name, notebook_type)
SELECT DISTINCT h.user_id, 'Sổ tay điểm yếu', 'WEAKNESS'
FROM jlpt_attempt_history h
WHERE NOT EXISTS (
    SELECT 1 FROM user_notebook n WHERE n.user_id = h.user_id AND n.notebook_type = 'WEAKNESS'
);

INSERT IGNORE INTO user_notebook_entry(notebook_id, vocabulary_id)
SELECT n.id, q.target_vocabulary_id
FROM jlpt_attempt_history h
JOIN jlpt_attempt_answer_snapshot s ON s.attempt_id = h.id
JOIN jlpt_question q ON q.target_vocabulary_id IS NOT NULL
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id AND e.exam_code = h.exam_code
JOIN user_notebook n ON n.user_id = h.user_id AND n.notebook_type = 'WEAKNESS'
    AND n.id = (SELECT MIN(n2.id) FROM user_notebook n2
                WHERE n2.user_id = h.user_id AND n2.notebook_type = 'WEAKNESS')
WHERE p.part_code = 'vocabulary'
  AND (h.part_code IS NULL OR h.part_code = p.part_code)
  AND JSON_CONTAINS_PATH(s.answers_json, 'one', CONCAT('$."', q.id, '"'))
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.answers_json, CONCAT('$."', q.id, '"'))) AS UNSIGNED)
      BETWEEN 1 AND 4
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.answers_json, CONCAT('$."', q.id, '"'))) AS UNSIGNED)
      <> q.correct_option;

COMMIT;
SET SQL_SAFE_UPDATES = @previous_safe_updates;

SELECT COUNT(*) AS goi_questions_with_target
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
WHERE p.part_code = 'vocabulary' AND q.target_vocabulary_id IS NOT NULL;

-- ===== 044_jlpt_goi_reading_and_lemma_backfill.sql =====
USE jlas;
SET NAMES utf8mb4;

-- Second conservative pass: a unique reading or a uniquely matched common
-- inflected form can point to an existing vocabulary entry.
DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_source;
CREATE TEMPORARY TABLE tmp_jlpt_goi_source AS
SELECT q.id AS question_id, m.format_code,
       NULLIF(TRIM(CASE
           WHEN m.format_code IN ('kanji_reading', 'paraphrase') THEN
               REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   REGEXP_SUBSTR(q.question_html, '<u>.*?</u>'),
                   '<rt>.*?</rt>', ''), '<rp>.*?</rp>', ''), '<[^>]+>', '')
           WHEN m.format_code = 'usage' THEN
               REGEXP_REPLACE(q.question_text, '^[0-9]+[.．][[:space:]]*', '')
           ELSE o.option_text
       END), '') AS expression,
       NULLIF(TRIM(CASE
           WHEN m.format_code = 'kanji_reading' THEN o.option_text
           WHEN m.format_code = 'orthography' THEN
               REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   REGEXP_SUBSTR(q.question_html, '<u>.*?</u>'),
                   '<rt>.*?</rt>', ''), '<rp>.*?</rp>', ''), '<[^>]+>', '')
           WHEN m.format_code = 'context' AND o.option_text REGEXP '^[ぁ-ゟー]+$'
               THEN o.option_text
           ELSE NULL
       END), '') AS reading
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
LEFT JOIN jlpt_option o ON o.question_id = q.id AND o.option_number = q.correct_option
WHERE p.part_code = 'vocabulary'
  AND m.format_code IN ('kanji_reading', 'orthography', 'context', 'paraphrase', 'usage')
  AND q.target_vocabulary_id IS NULL;

ALTER TABLE tmp_jlpt_goi_source ADD PRIMARY KEY (question_id);

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_suffix_rule;
CREATE TEMPORARY TABLE tmp_jlpt_suffix_rule (surface VARCHAR(8), base VARCHAR(8));
INSERT INTO tmp_jlpt_suffix_rule(surface, base) VALUES
('った','る'),('った','う'),('った','つ'),
('って','る'),('って','う'),('って','つ'),
('いた','く'),('いて','く'),('いだ','ぐ'),('いで','ぐ'),
('した','す'),('して','す'),
('んだ','む'),('んだ','ぶ'),('んだ','ぬ'),
('んで','む'),('んで','ぶ'),('んで','ぬ'),
('た','る'),('て','る'),('く','い'),('かった','い'),
('な',''),('に','');

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_reading_candidate;
CREATE TEMPORARY TABLE tmp_jlpt_goi_reading_candidate AS
SELECT s.question_id, v.id AS vocabulary_id
FROM tmp_jlpt_goi_source s
JOIN vocabulary_entry v
  ON v.reading COLLATE utf8mb4_0900_as_cs = s.reading COLLATE utf8mb4_0900_as_cs
 AND (s.format_code = 'context' AND s.expression = s.reading
      OR s.format_code IN ('kanji_reading', 'orthography')
         AND v.expression COLLATE utf8mb4_0900_as_cs = s.expression COLLATE utf8mb4_0900_as_cs)
WHERE s.reading IS NOT NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_lemma_candidate;
CREATE TEMPORARY TABLE tmp_jlpt_goi_lemma_candidate AS
SELECT s.question_id, v.id AS vocabulary_id
FROM tmp_jlpt_goi_source s
JOIN tmp_jlpt_suffix_rule r
  ON RIGHT(s.expression, CHAR_LENGTH(r.surface)) = r.surface
 AND CHAR_LENGTH(s.expression) > CHAR_LENGTH(r.surface)
JOIN vocabulary_entry v
  ON v.expression COLLATE utf8mb4_0900_as_cs =
     CONCAT(LEFT(s.expression, CHAR_LENGTH(s.expression) - CHAR_LENGTH(r.surface)), r.base)
         COLLATE utf8mb4_0900_as_cs
 AND (s.reading IS NULL OR
      RIGHT(s.reading, CHAR_LENGTH(r.surface)) = r.surface
      AND v.reading COLLATE utf8mb4_0900_as_cs =
          CONCAT(LEFT(s.reading, CHAR_LENGTH(s.reading) - CHAR_LENGTH(r.surface)), r.base)
              COLLATE utf8mb4_0900_as_cs);

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_candidate;
CREATE TEMPORARY TABLE tmp_jlpt_goi_candidate AS
SELECT question_id,vocabulary_id FROM tmp_jlpt_goi_reading_candidate
UNION ALL
SELECT question_id,vocabulary_id FROM tmp_jlpt_goi_lemma_candidate;

DROP TEMPORARY TABLE IF EXISTS tmp_jlpt_goi_unique;
CREATE TEMPORARY TABLE tmp_jlpt_goi_unique AS
SELECT question_id, MIN(vocabulary_id) AS vocabulary_id
FROM tmp_jlpt_goi_candidate
GROUP BY question_id
HAVING COUNT(DISTINCT vocabulary_id) = 1;
ALTER TABLE tmp_jlpt_goi_unique ADD PRIMARY KEY (question_id);

SET @previous_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
START TRANSACTION;

UPDATE jlpt_question q
JOIN tmp_jlpt_goi_unique u ON u.question_id = q.id
SET q.target_vocabulary_id = u.vocabulary_id
WHERE q.target_vocabulary_id IS NULL;

INSERT IGNORE INTO user_notebook_entry(notebook_id, vocabulary_id)
SELECT n.id, q.target_vocabulary_id
FROM jlpt_attempt_history h
JOIN jlpt_attempt_answer_snapshot s ON s.attempt_id = h.id
JOIN jlpt_question q ON q.target_vocabulary_id IS NOT NULL
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id AND e.exam_code = h.exam_code
JOIN user_notebook n ON n.user_id = h.user_id AND n.notebook_type = 'WEAKNESS'
    AND n.id = (SELECT MIN(n2.id) FROM user_notebook n2
                WHERE n2.user_id = h.user_id AND n2.notebook_type = 'WEAKNESS')
WHERE p.part_code = 'vocabulary'
  AND (h.part_code IS NULL OR h.part_code = p.part_code)
  AND JSON_CONTAINS_PATH(s.answers_json, 'one', CONCAT('$."', q.id, '"'))
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.answers_json, CONCAT('$."', q.id, '"'))) AS UNSIGNED)
      BETWEEN 1 AND 4
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.answers_json, CONCAT('$."', q.id, '"'))) AS UNSIGNED)
      <> q.correct_option;

COMMIT;
SET SQL_SAFE_UPDATES = @previous_safe_updates;

SELECT COUNT(*) AS goi_questions_with_target
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
WHERE p.part_code = 'vocabulary' AND q.target_vocabulary_id IS NOT NULL;
