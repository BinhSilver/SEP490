-- 094) [JLAS-W0108] F238: CP kiểm tra tuần (giao tiếp + JLPT) và CP chủ đề giao tiếp đạt trong ngày được cộng chung một lần khi xong ngày học
--      (một hộp quà, không hiện quà lẻ). Chỉ thêm cột vào bảng lộ trình của mình. Chạy lại an toàn.
SET NAMES utf8mb4;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_user_path' AND column_name = 'week_pass_monday');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_user_path ADD COLUMN week_pass_monday DATE NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_user_path' AND column_name = 'week_pass_at');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_user_path ADD COLUMN week_pass_at DATETIME(6) NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_user_path' AND column_name = 'week_cp_owed');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_user_path ADD COLUMN week_cp_owed TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_user_topic' AND column_name = 'cp_day');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_user_topic ADD COLUMN cp_day DATE NULL', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Lộ trình JLPT: CP kiểm tra tuần đạt khi ngày chưa xong được giữ trên dòng ngày hôm đó, cộng chung khi xong ngày.
SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_daily_plan' AND column_name = 'week_cp_owed');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_daily_plan ADD COLUMN week_cp_owed SMALLINT UNSIGNED NOT NULL DEFAULT 0', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
