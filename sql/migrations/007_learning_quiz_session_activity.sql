-- [JLAS-W0108] P4 — anchor hoạt động riêng cho quiz per-activity (warm-up grammar).
-- LearningQuizSession đang khóa đề theo (quiz_type, lesson_id) chỉ đủ cho LESSON/GATE; warm-up grammar
-- quiz trỏ tới một grammar_point (không phải lesson), nên cần cột activity_id giữ grammarPointId.
-- null cho LESSON/GATE; chỉ GRAMMAR (và sau này FLASHCARD/R-L nếu cần) dùng. Không xóa lịch sử cũ.

ALTER TABLE learning_quiz_session
    ADD COLUMN activity_id BIGINT NULL;
