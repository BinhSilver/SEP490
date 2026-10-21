-- [JLAS-SHADOWING-EMBED] Shadowing: video YouTube phát bằng trình phát nhúng của YouTube, không tải về server.
-- Riêng video mà chủ kênh không cho nhúng thì vẫn tải về và phát từ file như trước.
--   video.playback_mode: 'EMBED' (phát qua YouTube) | 'FILE' (phát file đã tải) | NULL (dòng cũ: có file sẵn,
--   như video mẫu, thì phát file; không có file thì phát qua YouTube). Không xóa file nào.
-- Chạy lại an toàn.
SET @shadowing_playback_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video' AND column_name = 'playback_mode');
SET @shadowing_playback_ddl = IF(@shadowing_playback_exists = 0,
    'ALTER TABLE video ADD COLUMN playback_mode VARCHAR(10) NULL AFTER moderation_summary',
    'SELECT 1');
PREPARE shadowing_playback_stmt FROM @shadowing_playback_ddl;
EXECUTE shadowing_playback_stmt;
DEALLOCATE PREPARE shadowing_playback_stmt;
