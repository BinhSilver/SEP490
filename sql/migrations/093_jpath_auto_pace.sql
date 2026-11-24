-- 092) [JLAS-W0108] F237: tự tăng số từ mỗi ngày sau ngày lỡ học (lộ trình JLPT). Chạy lại an toàn.
--      jpath_user_path: pace_milestone / pace_milestone_level (ngày xong cấp dự kiến lúc đầu, mốc khi không có ngày thi),
--      auto_pace_off (người học bấm "Hoàn tác"). jpath_daily_plan: auto_words (số từ hôm nay đã tăng), auto_finish
--      (ngày xong mới khi tăng +30% vẫn chưa kịp). Bảng ở sql/learning/learning_schema.sql đã có các cột này.
--      ddl-auto=validate: chạy file này trước khi khởi động bản jar có F237.
SET NAMES utf8mb4;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_user_path' AND column_name = 'pace_milestone');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_user_path ADD COLUMN pace_milestone DATE NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_user_path' AND column_name = 'pace_milestone_level');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_user_path ADD COLUMN pace_milestone_level VARCHAR(2) NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_user_path' AND column_name = 'auto_pace_off');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_user_path ADD COLUMN auto_pace_off TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_daily_plan' AND column_name = 'auto_words');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_daily_plan ADD COLUMN auto_words SMALLINT UNSIGNED NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_daily_plan' AND column_name = 'auto_finish');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_daily_plan ADD COLUMN auto_finish DATE NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
