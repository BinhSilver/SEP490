-- [JLAS-SPEED-RECALL-TIME] Go phan xa: nguoi hoc chon thoi gian moi tu (6-60 giay) khi tao phien.
--   flashcard_speed_recall_session.time_limit_seconds: so giay moi tu; NULL = tu dong (6 giay, giam dan con 3 giay
--   khi tra loi dung lien tiep), nen phien cu khong doi hanh vi.
-- Chay lai an toan. SpeedRecallTimeLimitSchemaInitializer cung tu them cot khi khoi dong.
SET @sr_time_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'flashcard_speed_recall_session' AND column_name = 'time_limit_seconds');
SET @sr_time_ddl = IF(@sr_time_exists = 0,
    'ALTER TABLE flashcard_speed_recall_session ADD COLUMN time_limit_seconds TINYINT UNSIGNED NULL',
    'SELECT 1');
PREPARE sr_time_stmt FROM @sr_time_ddl;
EXECUTE sr_time_stmt;
DEALLOCATE PREPARE sr_time_stmt;
