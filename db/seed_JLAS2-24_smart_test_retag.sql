-- ============================================================================
-- [JLAS2-24] seed_JLAS2-24_smart_test_retag.sql
-- Gắn "vừa bài vừa điểm ngữ pháp" cho 10 câu ngữ pháp N5/N4 trong smart test.
--
-- Bối cảnh: các câu hỏi hiện chỉ gắn tag thô (PARTICLE / VERB / N4-GRAMMAR),
-- không chỉ ra ĐIỂM NGỮ PHÁP cụ thể nên kết quả không suy ra được lộ trình.
-- File này thay tag thô bằng: mẫu chính (assessment_question.grammar_point_id)
-- + bài (assessment_question.knowledge_tag_id = tag của lesson chứa mẫu đó).
--
-- Thay đổi trong file này:
--   1. Thêm 2 grammar_point mới (thiếu trong catalog):
--        - 301: trợ từ が (chủ ngữ)          -> N5-B09 (lesson 9)
--        - 302: V て形 (cách chia động từ)    -> N5-B14 (lesson 14)
--   2. Gán mẫu chính + bài cho 10 câu (thay tag thô).
--   3. 9711 thêm mẫu phụ 154 vào question_grammar_point (chỉ để hiển thị).
--   4. 9715/9716 chuyển jlpt_level N4 -> N5 (mẫu 98 thuộc N5-B18).
--
-- Idempotent: chạy lại nhiều lần cho kết quả giống nhau.
--
-- Bảng mapping mẫu chính (đã đối chiếu catalog hiện có):
--   9701 「は」chủ đề          -> gp 1   (N5-B01)  [N1 は N2 です]
--   9702 「が」chủ ngữ          -> gp 301 (N5-B09)  [mới]
--   9703 て形 nhóm 2 (食べる)   -> gp 302 (N5-B14)  [mới]
--   9704 て形 nhóm 1 (書く)     -> gp 302 (N5-B14)  [mới]
--   9711 〜てしまう (nghĩa)     -> gp 153 (N4-B29)  [lỡ tay] + phụ 154 [xong hẳn]
--   9712 忘れてしまった          -> gp 153 (N4-B29)
--   9715 〜ことができる         -> gp 98  (N5-B18)  -> chuyển N5
--   9716 話すことができる       -> gp 98  (N5-B18)  -> chuyển N5
--   9719 〜ながら               -> gp 145 (N4-B28)
--   9720 聞きながら             -> gp 145 (N4-B28)
-- ============================================================================

-- 1) Thêm 2 mẫu ngữ pháp mới -----------------------------------------------
INSERT INTO grammar_point (id, lesson_id, tag_id, pattern, meaning, example, note, order_index) VALUES
  (301, 9, NULL,
   'N が V ます（だれが・なにが）',
   'Đánh dấu chủ ngữ (người/vật thực hiện hành động)\n「が」đứng sau chủ ngữ khi chủ ngữ là thông tin mới; câu hỏi về ai/cái gì (だれが・なにが) thì luôn dùng が, không dùng は.',
   'だれが きましたか。\nAi đã đến?',
   'は nêu chủ đề, が nêu chủ ngữ của hành động — khi chủ ngữ là tin mới thì dùng が.',
   9),
  (302, 14, NULL,
   'V て形（cách chia động từ）',
   'Cách chia động từ sang thể て\nNhóm 1: う・つ・る → って, む・ぶ・ぬ → んで, く → いて, ぐ → いで, す → して. Nhóm 2: bỏ る thêm て. Nhóm 3: する → して, 来る → きて.',
   'たべる → たべて、 かく → かいて。',
   'Thể て là gốc của てください・ています・てしまいました… cần thuộc cách chia.',
   5)
AS new
ON DUPLICATE KEY UPDATE
  pattern     = new.pattern,
  meaning     = new.meaning,
  example     = new.example,
  note        = new.note,
  order_index = new.order_index;

-- 2) Gán mẫu chính + bài cho 10 câu -----------------------------------------
-- knowledge_tag_id được suy ra từ lesson của mẫu (grammar_point -> lesson -> tag),
-- nên bài luôn khớp với mẫu. jlpt_level chỉ đổi cho 9715/9716 (về N5).
UPDATE assessment_question q
JOIN grammar_point gp
  ON gp.id = CASE q.id
       WHEN 9701 THEN 1
       WHEN 9702 THEN 301
       WHEN 9703 THEN 302
       WHEN 9704 THEN 302
       WHEN 9711 THEN 153
       WHEN 9712 THEN 153
       WHEN 9715 THEN 98
       WHEN 9716 THEN 98
       WHEN 9719 THEN 145
       WHEN 9720 THEN 145
     END
JOIN lesson l ON l.id = gp.lesson_id
JOIN tag t    ON t.id = l.knowledge_tag_id
SET q.grammar_point_id = gp.id,
    q.knowledge_tag_id = t.id,
    q.jlpt_level       = IF(q.id IN (9715, 9716), 'N5', q.jlpt_level)
WHERE q.id IN (9701, 9702, 9703, 9704, 9711, 9712, 9715, 9716, 9719, 9720);

-- 3) Mẫu phụ cho 9711 (chỉ để hiển thị, không tính điểm) --------------------
INSERT INTO question_grammar_point (question_id, grammar_point_id) VALUES
  (9711, 154)
ON DUPLICATE KEY UPDATE grammar_point_id = VALUES(grammar_point_id);
