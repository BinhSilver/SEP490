-- ============================================================
-- [JLAS-W0132] Learning Path E2E — seed Smart Test dữ liệu cho demo learner
-- ------------------------------------------------------------------
-- Demo learner 9001 (learner@jlas.local / Jlas@123) hiện chưa có kết quả
-- Smart Test (assessment_attempt = 0). Seed này tạo 1 kết quả Smart Test
-- "SUBMITTED" với đáp án sai đủ 5 skill để `SmartTestLearningProfileSource`
-- kết luận được 5 loại điểm yếu (KANJI/VOCAB/GRAMMAR/READING/LISTENING),
-- làm đầu vào cho POST /api/learning/paths.
--
-- Mục tiêu E2E: Smart Test → weakness → tạo path → warm-up (flashcard Kanji/
-- từ vựng, ngữ pháp, bài đọc/nghe) → lesson quiz → gate quiz → replan.
-- CP/Streak = ngoài phạm vi (chờ W0127, người khác đảm nhận).
--
-- Idempotent — chạy lại an toàn (guard theo attempt_id).
-- ============================================================
SET NAMES utf8mb4;

-- 1) Learning survey cho learner 9001 (bắt buộc khi tạo path).
INSERT INTO user_learning_survey
    (user_id, goal, target_level, self_reported_level, study_duration_months,
     daily_study_minutes, skills, completed_at)
SELECT 9001, 'JLPT', 'N4', 'N4', 12, 30, 'KANJI,VOCABULARY,GRAMMAR,READING,LISTENING', NOW()
WHERE NOT EXISTS (SELECT 1 FROM user_learning_survey WHERE user_id = 9001);

-- 2) Assessment attempt (Smart Test đã nộp) — id cao tránh đụng seed khác.
INSERT INTO assessment_attempt
    (id, user_id, attempt_type, status, started_at, submitted_at, quiz_id, score_percent)
SELECT 19001, 9001, 'SMART_TEST', 'SUBMITTED', NOW() - INTERVAL 20 MINUTE, NOW(), NULL, 42.00
WHERE NOT EXISTS (SELECT 1 FROM assessment_attempt WHERE id = 19001);

-- 3) Smart Test session (MapsId = attempt_id).
INSERT INTO smart_test_session
    (attempt_id, goal, target_level, self_reported_level, study_duration_months,
     daily_study_minutes, estimated_overall_level, overall_mastery_score, readiness_level)
SELECT 19001, 'JLPT', 'N4', 'N4', 12, 30, 'N4', 42.00, 'LOW'
WHERE NOT EXISTS (SELECT 1 FROM smart_test_session WHERE attempt_id = 19001);

-- 4) Đáp án Smart Test (question_id N4 thật, câu sai đủ 5 skill).
--    Sai: selected_option != correct_option (is_correct=0); Đúng: is_correct=1.
INSERT INTO assessment_answer
    (attempt_id, question_id, selected_option, is_correct, answered_at)
SELECT a.attempt_id, a.question_id, a.selected_option, a.is_correct, a.answered_at
FROM (
    SELECT 19001 AS attempt_id, 151  AS question_id, 2 AS selected_option, 0 AS is_correct, NOW() AS answered_at
    UNION ALL SELECT 19001, 153,  3, 1, NOW()
    UNION ALL SELECT 19001, 901,  2, 0, NOW()
    UNION ALL SELECT 19001, 902,  2, 1, NOW()
    UNION ALL SELECT 19001, 1551, 2, 0, NOW()
    UNION ALL SELECT 19001, 1552, 2, 1, NOW()
    UNION ALL SELECT 19001, 1801, 2, 0, NOW()
    UNION ALL SELECT 19001, 1802, 3, 0, NOW()
    UNION ALL SELECT 19001, 1803, 3, 1, NOW()
    UNION ALL SELECT 19001, 2051, 2, 0, NOW()
    UNION ALL SELECT 19001, 2052, 3, 0, NOW()
    UNION ALL SELECT 19001, 2053, 3, 1, NOW()
) a
WHERE NOT EXISTS (SELECT 1 FROM assessment_answer WHERE attempt_id = 19001);



