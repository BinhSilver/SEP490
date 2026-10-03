-- J-LAS full-spec release gate audit (read-only)
-- Run after schema, migrations and candidate seed are applied.
-- Every query returns a violation_count expected to be zero unless INFORMATIONAL.
-- Replace this cutover for every target database before running the audit.
SET @migration_010_cutover = TIMESTAMP('2026-10-02 15:18:05.300814');

-- A. Learning completion <-> qualifying JLPT attempt soft-link.
SELECT 'A1_POST_CUTOVER_COMPLETED_WITHOUT_ATTEMPT' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path
WHERE experience_version = 'INTERACTIVE_V2' AND status = 'COMPLETED'
  AND completed_at >= @migration_010_cutover AND completion_attempt_id IS NULL;

SELECT 'A2_ATTEMPT_ON_NON_COMPLETED_PATH' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path
WHERE completion_attempt_id IS NOT NULL AND (status <> 'COMPLETED' OR completed_at IS NULL);

SELECT 'A3_INVALID_COMPLETION_ATTEMPT' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path p
LEFT JOIN assessment_attempt a ON a.id = p.completion_attempt_id
WHERE p.completion_attempt_id IS NOT NULL
  AND (a.id IS NULL OR a.user_id <> p.user_id OR a.attempt_type <> 'JLPT' OR a.status <> 'SUBMITTED');

SELECT 'A4_ATTEMPT_REUSED_ACROSS_PATHS' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT completion_attempt_id FROM user_learning_path
    WHERE completion_attempt_id IS NOT NULL
    GROUP BY completion_attempt_id HAVING COUNT(*) > 1
) duplicated_attempt;

-- INFORMATIONAL: classify manually; pre-cutover rows do not auto-fail release.
SELECT 'A0_INFORMATIONAL_LEGACY_COMPLETION_WITHOUT_ATTEMPT' AS audit_code, COUNT(*) AS row_count
FROM user_learning_path
WHERE status = 'COMPLETED' AND completion_attempt_id IS NULL
  AND (completed_at IS NULL OR completed_at < @migration_010_cutover);

-- B. Published JLPT exam/section/attempt integrity.
SELECT 'B1_PUBLISHED_EXAM_WITHOUT_POLICY' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_exam
WHERE status = 'PUBLISHED' AND (policy_version IS NULL OR TRIM(policy_version) = '');

SELECT 'B2_PUBLISHED_EXAM_WITHOUT_SECTION' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT e.id FROM jlpt_exam e
    LEFT JOIN jlpt_exam_section s ON s.exam_id = e.id
    WHERE e.status = 'PUBLISHED'
    GROUP BY e.id HAVING COUNT(s.id) = 0
) exam_without_section;

SELECT 'B3_SECTION_WITH_INVALID_QUIZ' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_exam_section s
JOIN jlpt_exam e ON e.id = s.exam_id
JOIN assessment_quiz q ON q.id = s.quiz_id
WHERE e.status = 'PUBLISHED' AND (q.quiz_type <> 'JLPT_SECTION' OR q.status <> 'PUBLISHED');

SELECT 'B4_SECTION_SCORE_EXCEEDS_MAX' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_section_result r
JOIN jlpt_exam_section s ON s.id = r.section_id
WHERE r.score > s.max_score;

SELECT 'B5_SUBMITTED_ATTEMPT_MISSING_SECTION_RESULT' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT ja.attempt_id, s.id AS section_id
    FROM jlpt_exam_attempt ja
    JOIN assessment_attempt a ON a.id = ja.attempt_id
    JOIN jlpt_exam_section s ON s.exam_id = ja.exam_id
    LEFT JOIN jlpt_section_result r ON r.attempt_id = ja.attempt_id AND r.section_id = s.id
    WHERE a.status = 'SUBMITTED' AND r.attempt_id IS NULL
) missing_result;

SELECT 'B6_SECTION_RESULT_FROM_ANOTHER_EXAM' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_section_result r
JOIN jlpt_exam_attempt ja ON ja.attempt_id = r.attempt_id
JOIN jlpt_exam_section s ON s.id = r.section_id
WHERE s.exam_id <> ja.exam_id;

-- Presence is checked here; the configured accepted-policy allow-list is checked by app tests.
SELECT 'B7_SUBMITTED_ATTEMPT_WITHOUT_POLICY_SNAPSHOT' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_exam_attempt ja
JOIN assessment_attempt a ON a.id = ja.attempt_id
WHERE a.status = 'SUBMITTED' AND (ja.policy_version IS NULL OR TRIM(ja.policy_version) = '');

-- C. Assessment question and active usage integrity.
SELECT 'C1_ACTIVE_USAGE_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM assessment_question_usage WHERE active = TRUE AND review_status <> 'APPROVED';

SELECT 'C2_ACTIVE_USAGE_WITH_INACTIVE_QUESTION' AS audit_code, COUNT(*) AS violation_count
FROM assessment_question_usage u
JOIN assessment_question q ON q.id = u.question_id
WHERE u.active = TRUE AND q.is_active = FALSE;

SELECT 'C3_ACTIVE_USAGE_WITHOUT_BLUEPRINT_OR_SECTION' AS audit_code, COUNT(*) AS violation_count
FROM assessment_question_usage
WHERE active = TRUE AND (blueprint_code IS NULL OR TRIM(blueprint_code) = ''
  OR section_code IS NULL OR TRIM(section_code) = '');

SELECT 'C4_PUBLISHED_SECTION_WITHOUT_QUESTION' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT s.id FROM jlpt_exam_section s
    JOIN jlpt_exam e ON e.id = s.exam_id
    LEFT JOIN assessment_quiz_question qq ON qq.quiz_id = s.quiz_id
    WHERE e.status = 'PUBLISHED'
    GROUP BY s.id HAVING COUNT(qq.question_id) = 0
) section_without_question;

SELECT 'C5_PUBLISHED_EXAM_QUESTION_LEVEL_MISMATCH' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_exam e
JOIN jlpt_exam_section s ON s.exam_id = e.id
JOIN assessment_quiz_question qq ON qq.quiz_id = s.quiz_id
JOIN assessment_question q ON q.id = qq.question_id
WHERE e.status = 'PUBLISHED' AND q.jlpt_level <> e.jlpt_level;

SELECT 'C6_DUPLICATE_QUESTION_ACROSS_EXAM_SECTIONS' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT s.exam_id, qq.question_id FROM jlpt_exam_section s
    JOIN assessment_quiz_question qq ON qq.quiz_id = s.quiz_id
    GROUP BY s.exam_id, qq.question_id HAVING COUNT(*) > 1
) duplicate_question;

-- D. Learning source and goal-specific release readiness.
SELECT 'D1_PUBLISHED_PATH_SOURCE_WITHOUT_PUBLISHED_LESSON' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT t.id FROM textbook t
    LEFT JOIN unit u ON u.textbook_id = t.id
    LEFT JOIN lesson l ON l.unit_id = u.id AND l.status = 'PUBLISHED'
    WHERE t.is_learning_path_source = TRUE AND t.status = 'PUBLISHED'
    GROUP BY t.id HAVING COUNT(l.id) = 0
) source_without_lesson;

SELECT 'D2_ACTIVE_GOAL_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM learning_goal WHERE active = TRUE AND review_status <> 'APPROVED';

SELECT 'D3_ACTIVE_TOPIC_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM learning_topic WHERE active = TRUE AND review_status <> 'APPROVED';

SELECT 'D4_ACTIVE_COMPETENCY_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM learning_competency WHERE active = TRUE AND review_status <> 'APPROVED';

SELECT 'D5_LESSON_SCOPE_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM lesson_learning_scope s
JOIN learning_goal g ON g.id = s.goal_id
WHERE g.active = TRUE AND s.review_status <> 'APPROVED';

SELECT 'D6_APPROVED_SCOPE_WITH_UNPUBLISHED_LESSON' AS audit_code, COUNT(*) AS violation_count
FROM lesson_learning_scope s
JOIN lesson l ON l.id = s.lesson_id
JOIN learning_goal g ON g.id = s.goal_id
WHERE g.active = TRUE AND s.review_status = 'APPROVED' AND l.status <> 'PUBLISHED';

-- Travel and Daily Life are survey-only; BrSE/BA may use foundation assessment.
SELECT 'D7_SURVEY_ONLY_GOAL_REQUIRES_ASSESSMENT' AS audit_code, COUNT(*) AS violation_count
FROM learning_goal
WHERE goal_code IN ('TRAVEL', 'DAILY_LIFE') AND requires_assessment = TRUE;

-- INFORMATIONAL inventory. A missing/empty goal keeps the Release C slice blocked.
SELECT g.goal_code, g.review_status AS goal_review_status,
       COUNT(DISTINCT t.id) AS topic_count,
       COUNT(DISTINCT s.lesson_id) AS scoped_lesson_count,
       COUNT(DISTINCT CASE WHEN s.review_status = 'APPROVED' AND l.status = 'PUBLISHED'
                           THEN s.lesson_id END) AS approved_published_lesson_count
FROM learning_goal g
LEFT JOIN learning_topic t ON t.goal_id = g.id AND t.active = TRUE
LEFT JOIN lesson_learning_scope s ON s.goal_id = g.id
LEFT JOIN lesson l ON l.id = s.lesson_id
WHERE g.goal_code IN ('BA', 'TRAVEL', 'DAILY_LIFE')
GROUP BY g.id, g.goal_code, g.review_status
ORDER BY g.goal_code;
