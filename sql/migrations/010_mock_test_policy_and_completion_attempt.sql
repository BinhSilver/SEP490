-- [JLAS-W0108] Task 9 review fixes: a Mock Test result only completes a path when its policy-version snapshot
-- matches the exam's current policy, and a completed path remembers the one attempt that qualified it.

SET NAMES utf8mb4;

ALTER TABLE jlpt_exam
    ADD COLUMN policy_version VARCHAR(100) NULL AFTER passing_score;

ALTER TABLE jlpt_exam_attempt
    ADD COLUMN policy_version VARCHAR(100) NULL AFTER exam_id;

ALTER TABLE user_learning_path
    ADD COLUMN completion_attempt_id BIGINT UNSIGNED NULL AFTER completed_at;
