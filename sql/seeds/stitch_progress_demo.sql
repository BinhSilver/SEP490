-- Demo data for the Stitch learning analytics page.
-- Target account: huyphw2@gmail.com
-- MySQL 8.x. Run this after jlas_schema.sql and the user account exists.

START TRANSACTION;

SET @demo_email = CONVERT('huyphw2@gmail.com' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @demo_today = CURRENT_DATE();
SET @demo_user_id = (
    SELECT id
    FROM users
    WHERE email COLLATE utf8mb4_unicode_ci = @demo_email COLLATE utf8mb4_unicode_ci
    LIMIT 1
);

-- Stop instead of writing to an unexpected account when the email is missing.
SET @demo_user_exists = IF(@demo_user_id IS NULL, 0, 1);
SET @demo_user_id = IF(@demo_user_exists = 1, @demo_user_id, 0);

INSERT INTO user_learning_targets (
    user_id, daily_vocab_target, daily_quiz_target, daily_study_minutes
)
SELECT @demo_user_id, 15, 1, 20
WHERE @demo_user_exists = 1
ON DUPLICATE KEY UPDATE
    daily_vocab_target = VALUES(daily_vocab_target),
    daily_quiz_target = VALUES(daily_quiz_target),
    daily_study_minutes = VALUES(daily_study_minutes),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO user_streaks (
    user_id, current_streak, longest_streak, last_completed_date,
    freeze_count, selected_goal_days, onboarding_step, onboarding_completed,
    onboarding_reward_granted, reminder_time, reminder_enabled
)
    SELECT @demo_user_id, 12, 28, @demo_today, 1, 30, 'COMPLETED', TRUE, TRUE, '19:00:00', TRUE
WHERE @demo_user_exists = 1
ON DUPLICATE KEY UPDATE
    current_streak = VALUES(current_streak),
    longest_streak = VALUES(longest_streak),
    last_completed_date = VALUES(last_completed_date),
    freeze_count = VALUES(freeze_count),
    selected_goal_days = VALUES(selected_goal_days),
    onboarding_step = VALUES(onboarding_step),
    onboarding_completed = VALUES(onboarding_completed),
    onboarding_reward_granted = VALUES(onboarding_reward_granted),
    reminder_time = VALUES(reminder_time),
    reminder_enabled = VALUES(reminder_enabled),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO user_daily_progress (
    user_id, progress_date, vocabulary_learned, quizzes_completed,
    study_minutes, target_completed_at
)
VALUES
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), 18, 2, 24, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), '20:10:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 5 DAY), 12, 1, 18, NULL),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), 22, 3, 32, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), '21:05:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), 15, 1, 20, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), '19:30:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY), 9, 1, 14, NULL),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), 19, 2, 27, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), '20:20:00')),
    (@demo_user_id, CURRENT_DATE(), 14, 1, 20, TIMESTAMP(CURRENT_DATE(), '19:42:00'))
ON DUPLICATE KEY UPDATE
    vocabulary_learned = VALUES(vocabulary_learned),
    quizzes_completed = VALUES(quizzes_completed),
    study_minutes = VALUES(study_minutes),
    target_completed_at = VALUES(target_completed_at),
    updated_at = CURRENT_TIMESTAMP(6);

-- Remove only rows created by this demo seed, so the script is repeatable.
DELETE FROM user_credit_transaction
WHERE user_id = @demo_user_id
  AND note COLLATE utf8mb4_unicode_ci LIKE _utf8mb4'DEMO_STITCH_%' COLLATE utf8mb4_unicode_ci;

INSERT INTO user_credit_transaction (
    user_id, amount, reason, reference_type, reference_id, note, created_at
)
VALUES
    (@demo_user_id, 500, 'WELCOME_REWARD', 'STITCH_DEMO', 1, 'DEMO_STITCH_WELCOME', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 21 DAY), '09:00:00')),
    (@demo_user_id, 680, 'WEEKLY_REWARD', 'STITCH_DEMO', 2, 'DEMO_STITCH_WEEKLY', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 12 DAY), '20:00:00')),
    (@demo_user_id, 120, 'TARGET_COMPLETE', 'STITCH_DEMO', 3, 'DEMO_STITCH_TARGET', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY), '19:42:00')),
    (@demo_user_id, 30, 'QUIZ_COMPLETE', 'STITCH_DEMO', 4, 'DEMO_STITCH_QUIZ', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY), '08:15:00')),
    (@demo_user_id, -80, 'ITEM_PURCHASE', 'STITCH_DEMO', 5, 'DEMO_STITCH_FREEZE', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 8 DAY), '21:00:00'));

UPDATE users
SET credit_balance = 1250,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = @demo_user_id;

COMMIT;

SELECT
    u.id,
    u.email,
    u.credit_balance,
    s.current_streak,
    s.longest_streak,
    COUNT(DISTINCT p.id) AS progress_days,
    COUNT(DISTINCT c.id) AS seeded_credit_rows
FROM users u
LEFT JOIN user_streaks s ON s.user_id = u.id
LEFT JOIN user_daily_progress p ON p.user_id = u.id
LEFT JOIN user_credit_transaction c
       ON c.user_id = u.id
      AND c.note COLLATE utf8mb4_unicode_ci LIKE _utf8mb4'DEMO_STITCH_%' COLLATE utf8mb4_unicode_ci
WHERE u.id = @demo_user_id
GROUP BY u.id, u.email, u.credit_balance, s.current_streak, s.longest_streak;
