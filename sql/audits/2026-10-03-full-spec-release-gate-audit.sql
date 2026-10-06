-- J-LAS full-spec release gate audit (read-only)
-- Run after schema, migrations and candidate seed are applied.
-- Every query returns a violation_count expected to be zero unless INFORMATIONAL.
-- A. Learning option A: progress completes the path. Mock Test is self-assessment only;
-- completion_attempt_id is legacy/nullable and never a release-failing prerequisite.
SELECT 'A1_COMPLETED_PATH_WITHOUT_GOAL_PACKAGE' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path p
WHERE p.experience_version = 'INTERACTIVE_V2' AND p.status = 'COMPLETED'
  AND NOT EXISTS (
      SELECT 1 FROM learning_path_package pkg
      WHERE pkg.learning_path_id = p.id AND pkg.phase = 'GOAL_LEARNING'
  );

SELECT 'A2_COMPLETED_PATH_WITH_UNFINISHED_GOAL' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path p
WHERE p.experience_version = 'INTERACTIVE_V2' AND p.status = 'COMPLETED'
  AND EXISTS (
      SELECT 1 FROM learning_path_package pkg
      WHERE pkg.learning_path_id = p.id AND pkg.phase = 'GOAL_LEARNING' AND pkg.status <> 'COMPLETED'
  );

SELECT 'A3_FINISHED_GOAL_PATH_NOT_COMPLETED' AS audit_code, COUNT(*) AS violation_count
FROM user_learning_path p
WHERE p.experience_version = 'INTERACTIVE_V2' AND p.status = 'ACTIVE'
  AND EXISTS (
      SELECT 1 FROM learning_path_package pkg
      WHERE pkg.learning_path_id = p.id AND pkg.phase = 'GOAL_LEARNING'
  )
  AND NOT EXISTS (
      SELECT 1 FROM learning_path_package pkg
      WHERE pkg.learning_path_id = p.id AND pkg.phase = 'GOAL_LEARNING' AND pkg.status <> 'COMPLETED'
  );

SELECT 'A4_DUPLICATE_PACKAGE_ORDER' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT learning_path_id, order_index FROM learning_path_package
    GROUP BY learning_path_id, order_index HAVING COUNT(*) > 1
) duplicated_package_order;

-- INFORMATIONAL only: legacy soft-links may remain, but option A never requires them.
SELECT 'A0_INFORMATIONAL_LEGACY_COMPLETION_ATTEMPT' AS audit_code, COUNT(*) AS row_count
FROM user_learning_path
WHERE completion_attempt_id IS NOT NULL;

-- B. Canonical JLPT self-assessment content integrity (exam -> part -> mondai -> question).
SELECT 'B1_PUBLISHED_EXAM_NOT_APPROVED' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_exam
WHERE status = 'PUBLISHED' AND review_status <> 'APPROVED';

SELECT 'B2_PUBLISHED_EXAM_WITHOUT_PART' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT e.id FROM jlpt_exam e
    LEFT JOIN jlpt_part p ON p.exam_id = e.id
    WHERE e.status = 'PUBLISHED'
    GROUP BY e.id HAVING COUNT(p.id) = 0
) exam_without_part;

SELECT 'B3_PART_WITHOUT_MONDAI' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT p.id FROM jlpt_part p
    JOIN jlpt_exam e ON e.id = p.exam_id
    LEFT JOIN jlpt_mondai m ON m.part_id = p.id
    WHERE e.status = 'PUBLISHED'
    GROUP BY p.id HAVING COUNT(m.id) = 0
) part_without_mondai;

SELECT 'B4_MONDAI_WITHOUT_QUESTION' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT m.id FROM jlpt_mondai m
    JOIN jlpt_part p ON p.id = m.part_id
    JOIN jlpt_exam e ON e.id = p.exam_id
    LEFT JOIN jlpt_question q ON q.mondai_id = m.id
    WHERE e.status = 'PUBLISHED'
    GROUP BY m.id HAVING COUNT(q.id) = 0
) mondai_without_question;

SELECT 'B5_CORRECT_OPTION_NOT_FOUND' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id
LEFT JOIN jlpt_option o ON o.question_id = q.id AND o.option_number = q.correct_option
WHERE e.status = 'PUBLISHED' AND q.correct_option IS NOT NULL AND o.id IS NULL;

SELECT 'B6_INVALID_AUDIO_CLIP_RANGE' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_audio_clip c
JOIN jlpt_question q ON q.id = c.question_id
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id
WHERE e.status = 'PUBLISHED'
  AND (TRIM(c.audio_url) = '' OR c.start_seconds < 0 OR c.end_seconds <= c.start_seconds);

SELECT 'B7_LISTENING_QUESTION_WITHOUT_AUDIO' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id
LEFT JOIN jlpt_audio_clip c ON c.question_id = q.id
WHERE e.status = 'PUBLISHED' AND LOWER(p.part_code) = 'listening' AND c.id IS NULL;

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

SELECT 'C4_PUBLISHED_QUESTION_WITHOUT_ANSWER' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_question q
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id
WHERE e.status = 'PUBLISHED' AND q.correct_option IS NULL AND q.correct_order IS NULL;

SELECT 'C5_QUESTION_TYPE_PART_MISMATCH' AS audit_code, COUNT(*) AS violation_count
FROM jlpt_question q
JOIN jlpt_question_type qt ON qt.id = q.question_type_id
JOIN jlpt_mondai m ON m.id = q.mondai_id
JOIN jlpt_part p ON p.id = m.part_id
JOIN jlpt_exam e ON e.id = p.exam_id
WHERE e.status = 'PUBLISHED' AND LOWER(qt.part_code) <> LOWER(p.part_code);

SELECT 'C6_DUPLICATE_SOURCE_QUESTION_IN_EXAM' AS audit_code, COUNT(*) AS violation_count
FROM (
    SELECT p.exam_id, q.source_question_id
    FROM jlpt_question q
    JOIN jlpt_mondai m ON m.id = q.mondai_id
    JOIN jlpt_part p ON p.id = m.part_id
    GROUP BY p.exam_id, q.source_question_id HAVING COUNT(*) > 1
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
