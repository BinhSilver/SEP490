-- Run once against an existing database before starting the updated application.
CREATE TABLE IF NOT EXISTS user_learning_survey (
    user_id BIGINT UNSIGNED NOT NULL,
    goal VARCHAR(30) NOT NULL,
    target_level VARCHAR(10) NULL,
    self_reported_level VARCHAR(20) NOT NULL,
    study_duration_months INT NULL,
    daily_study_minutes INT NULL,
    skills VARCHAR(100) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_learning_survey_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve onboarding completion for accounts that already supplied a Smart Test survey.
INSERT IGNORE INTO user_learning_survey
    (user_id, goal, target_level, self_reported_level, study_duration_months, daily_study_minutes, skills, completed_at)
SELECT a.user_id, s.goal, s.target_level, s.self_reported_level,
       s.study_duration_months, s.daily_study_minutes,
       GROUP_CONCAT(DISTINCT q.skill ORDER BY FIELD(q.skill,'KANJI','VOCABULARY','GRAMMAR','READING','LISTENING') SEPARATOR ','),
       a.started_at
FROM smart_test_session s
JOIN assessment_attempt a ON a.id=s.attempt_id
JOIN smart_test_session_question sq ON sq.attempt_id=s.attempt_id
JOIN assessment_question q ON q.id=sq.question_id
WHERE NOT EXISTS (SELECT 1 FROM smart_test_session newer
    JOIN assessment_attempt na ON na.id=newer.attempt_id
    WHERE na.user_id=a.user_id AND na.id>a.id)
GROUP BY a.user_id, s.goal, s.target_level, s.self_reported_level,
         s.study_duration_months, s.daily_study_minutes, a.started_at;
