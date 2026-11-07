-- [JLAS-AGE-RATING] Ngày sinh người dùng và nhãn độ tuổi cho video Shadowing.
--   users.date_of_birth: nhập lúc đăng ký; tài khoản cũ / đăng nhập Google để NULL và được hỏi khi mở video có nhãn tuổi.
--   video.min_age: tuổi tối thiểu để xem (13, 16 hoặc 18); NULL = mọi lứa tuổi, nên video cũ không đổi hành vi.
-- Entity đã map hai cột này (ddl-auto: validate), nên phải chạy trước khi khởi động bản code mới. Chạy lại an toàn.
SET @user_birth_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'date_of_birth');
SET @user_birth_ddl = IF(@user_birth_exists = 0,
    'ALTER TABLE users ADD COLUMN date_of_birth DATE NULL AFTER gender',
    'SELECT 1');
PREPARE user_birth_stmt FROM @user_birth_ddl;
EXECUTE user_birth_stmt;
DEALLOCATE PREPARE user_birth_stmt;

SET @video_min_age_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video' AND column_name = 'min_age');
SET @video_min_age_ddl = IF(@video_min_age_exists = 0,
    'ALTER TABLE video ADD COLUMN min_age TINYINT UNSIGNED NULL AFTER visibility',
    'SELECT 1');
PREPARE video_min_age_stmt FROM @video_min_age_ddl;
EXECUTE video_min_age_stmt;
DEALLOCATE PREPARE video_min_age_stmt;
