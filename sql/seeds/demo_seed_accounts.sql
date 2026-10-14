-- =====================================================================
-- J-LAS seed: tai khoan ma cac seed demo can nhung khong file nao tao.
--   huyphw2@gmail.com   LEARNER  id tu tang  mat khau Password123!
--     -> can cho sql/seeds/flashcard_demo_5_sets.sql, sql/seeds/stitch_progress_demo.sql
--   learner@jlas.local  LEARNER  id 9001     mat khau Jlas@123
--     -> can cho db/2026-09-27-w0132-e2e-seed.sql
--
-- CHI DUNG CHO MOI TRUONG PHAT TRIEN / DEMO (mat khau cong khai trong file nay).
-- Chay SAU jlas_seed_all.sql (can roles + premium_plans), TRUOC 3 file tren.
-- KHONG co lenh USE: chon schema truoc khi chay. Chay lai duoc (INSERT IGNORE theo email / id).
-- password_hash sinh bang BCryptPasswordEncoder() (cost 10), da kiem tra matches() = true.
-- =====================================================================
SET NAMES utf8mb4;

INSERT IGNORE INTO users
    (role_id, premium_plan_id, email, username, password_hash, login_type,
     full_name, gender, japanese_level, credit_balance, is_verified)
VALUES
    ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
     'huyphw2@gmail.com', 'huyphw2', '$2a$10$6rwQqFQw0OsYuuC2OMa3/uLm..ThobaeqDWqSaLAHQUG8GSoJHUmi',
     'LOCAL', 'Huy Demo Learner', 'OTHER', 'N5', 0, 1);

INSERT IGNORE INTO users
    (id, role_id, premium_plan_id, email, username, password_hash, login_type,
     full_name, gender, japanese_level, credit_balance, is_verified)
VALUES
    (9001, (SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
     'learner@jlas.local', 'learner', '$2a$10$0shXGY.A629NfOZ4yOp67Oaboo8HbAre0KepLjfBIlUQQzv77Ppl2',
     'LOCAL', 'E2E Demo Learner', 'OTHER', 'N4', 0, 1);

-- [JLAS2-51] Hoat dong demo: 6 hoc vien rieng, 28 ngay lich su moi nguoi.
-- Chay sau content/flashcard seed neu muon co tu yeu thich va bo the da hoc.
-- Hai tai khoan o tren van danh rieng cho Stitch/E2E, khong ghi de tien do cua ho.
-- Khong tao giao dich thanh toan, token hay lich su AI gia.
-- Chay lai cung ngay khong nhan doi du lieu. Ngay lich su ket thuc HOM QUA
-- de khong tao hoat dong trong tuong lai hoac muc tieu da hoan thanh cho hom nay.
DROP TEMPORARY TABLE IF EXISTS demo_active_learners;
CREATE TEMPORARY TABLE demo_active_learners (
    ordinal INT PRIMARY KEY,
    email VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
    username VARCHAR(50), full_name VARCHAR(150), level_code VARCHAR(10),
    streak_days INT, joined_days INT
);
INSERT INTO demo_active_learners VALUES
    (1, 'demo.minhanh@jlas.local', 'demo_minhanh', 'Nguyễn Minh Anh', 'N5', 7, 45),
    (2, 'demo.hoangnam@jlas.local', 'demo_hoangnam', 'Trần Hoàng Nam', 'N4', 12, 72),
    (3, 'demo.thuylinh@jlas.local', 'demo_thuylinh', 'Lê Thùy Linh', 'N3', 21, 110),
    (4, 'demo.giabao@jlas.local', 'demo_giabao', 'Phạm Gia Bảo', 'N5', 3, 18),
    (5, 'demo.phuongmai@jlas.local', 'demo_phuongmai', 'Đỗ Phương Mai', 'N4', 14, 60),
    (6, 'demo.ducminh@jlas.local', 'demo_ducminh', 'Vũ Đức Minh', 'N2', 28, 150);

-- Mat khau chung: Password123! (cung bcrypt hash demo da co o tren).
START TRANSACTION;
INSERT IGNORE INTO users
    (role_id, premium_plan_id, email, username, password_hash, login_type,
     full_name, gender, japanese_level, is_verified, created_at, last_login_at)
SELECT r.id, p.id, d.email, d.username,
       '$2a$10$6rwQqFQw0OsYuuC2OMa3/uLm..ThobaeqDWqSaLAHQUG8GSoJHUmi',
       'LOCAL', d.full_name, 'OTHER', d.level_code, 1,
       DATE_SUB(CURRENT_DATE(), INTERVAL d.joined_days DAY),
       DATE_SUB(NOW(6), INTERVAL d.ordinal HOUR)
FROM demo_active_learners d
JOIN roles r ON r.name = 'LEARNER'
JOIN premium_plans p ON p.name = 'FREE';

INSERT IGNORE INTO user_learning_targets
    (user_id, daily_vocab_target, daily_quiz_target, daily_study_minutes)
SELECT u.id, 15, 1, 20 FROM demo_active_learners d JOIN users u ON u.email = d.email;

INSERT IGNORE INTO user_daily_progress
    (user_id, progress_date, vocabulary_learned, quizzes_completed,
     study_minutes, target_completed_at, created_at)
WITH RECURSIVE days AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM days WHERE n < 28
)
SELECT u.id, DATE_SUB(CURRENT_DATE(), INTERVAL days.n DAY),
       IF(days.n <= d.streak_days, 15 + MOD(days.n + d.ordinal, 11), 5 + MOD(days.n, 9)),
       IF(days.n <= d.streak_days, 1 + MOD(days.n + d.ordinal, 3), 0),
       IF(days.n <= d.streak_days, 20 + MOD(days.n * d.ordinal, 26), 8 + MOD(days.n, 10)),
       IF(days.n <= d.streak_days,
          TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL days.n DAY), '19:30:00'), NULL),
       TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL days.n DAY), '18:00:00')
FROM demo_active_learners d JOIN users u ON u.email = d.email
CROSS JOIN days WHERE days.n <= d.joined_days;

INSERT IGNORE INTO user_streaks
    (user_id, current_streak, longest_streak, last_completed_date, selected_goal_days,
     onboarding_step, onboarding_completed, onboarding_reward_granted, reminder_time)
SELECT u.id, d.streak_days, d.streak_days, DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY),
       30, 'COMPLETED', TRUE, FALSE, '19:00:00'
FROM demo_active_learners d JOIN users u ON u.email = d.email;

-- Lien ket noi dung co that; khong phu thuoc ID co dinh, bo qua neu chua seed content.
INSERT IGNORE INTO dictionary_favorite (user_id, vocabulary_id, note, created_at)
SELECT u.id, v.id, 'Từ cần ôn lại trong tuần',
       TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL d.ordinal DAY), '18:00:00')
FROM demo_active_learners d JOIN users u ON u.email = d.email
JOIN (SELECT id, jlpt_level, ROW_NUMBER() OVER (PARTITION BY jlpt_level ORDER BY id) AS rn
      FROM vocabulary_entry WHERE dictionary_status = 'PUBLISHED') v
  ON v.jlpt_level = d.level_code AND v.rn <= 5 + d.ordinal;

INSERT IGNORE INTO flashcard_user_set_state
    (user_id, set_id, is_favorite, rating, first_completed_at, last_accessed_at)
SELECT u.id, f.id, 1, 4 + MOD(d.ordinal, 2),
       DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_SUB(NOW(6), INTERVAL d.ordinal HOUR)
FROM demo_active_learners d JOIN users u ON u.email = d.email
JOIN flashcard_set f ON f.visibility = 'PUBLIC' AND f.is_draft = 0
 AND f.moderation_summary = 'DEMO_FLASHCARD_5SETS';

INSERT IGNORE INTO user_notification
    (user_id, type, title, message, event_key, read_at, created_at)
SELECT u.id, 'WELCOME', 'Chào mừng bạn quay lại!',
       CONCAT('Bạn đã duy trì ', d.streak_days, ' ngày học liên tiếp. Tiếp tục ôn tập nhé!'),
       'DEMO_ACTIVE_ACCOUNT_GREETING', NULL, DATE_SUB(NOW(6), INTERVAL d.ordinal HOUR)
FROM demo_active_learners d JOIN users u ON u.email = d.email;

COMMIT;
DROP TEMPORARY TABLE demo_active_learners;

-- Kiem tra sau import: moi hoc vien moi co toi da 28 ngay lich su.
SELECT u.email, u.full_name, u.japanese_level, s.current_streak,
       COUNT(p.id) AS progress_days
FROM users u LEFT JOIN user_streaks s ON s.user_id = u.id
LEFT JOIN user_daily_progress p ON p.user_id = u.id
WHERE u.email IN ('demo.minhanh@jlas.local', 'demo.hoangnam@jlas.local',
                 'demo.thuylinh@jlas.local', 'demo.giabao@jlas.local',
                 'demo.phuongmai@jlas.local', 'demo.ducminh@jlas.local')
GROUP BY u.id, u.email, u.full_name, u.japanese_level, s.current_streak;
