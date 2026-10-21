-- [JLAS-SHADOWING-REPORT] Shadowing: người dùng báo cáo video (bản quyền / không phù hợp). Báo cáo đúng thì gỡ video
-- và chặn nguồn YouTube để không ai nhập lại được. Chạy lại an toàn.
--   shadowing_video_report: mỗi dòng là một báo cáo; status OPEN -> VALID | INVALID khi quản trị viên quyết định.
--   shadowing_blocked_source: nguồn đã bị gỡ vĩnh viễn, khoá dạng "youtube:<id 11 ký tự>" (khớp mọi dạng link).
-- video.moderation_status có thêm hai giá trị REPORTED (ẩn tạm chờ duyệt) và REMOVED (đã gỡ); cột VARCHAR(20) sẵn có.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS shadowing_video_report (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id        BIGINT UNSIGNED NOT NULL,
    reporter_id     BIGINT UNSIGNED NOT NULL,
    reason          VARCHAR(20)  NOT NULL,
    detail          VARCHAR(500) NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    decided_by      BIGINT UNSIGNED NULL,
    decided_at      DATETIME(6)  NULL,
    decision_reason VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_shadowing_video_report_video (video_id, status),
    KEY idx_shadowing_video_report_reporter (reporter_id, created_at),
    CONSTRAINT fk_shadowing_video_report_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_video_report_user FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_blocked_source (
    source_key VARCHAR(100) NOT NULL PRIMARY KEY,
    source_url TEXT NULL,
    reason     VARCHAR(500) NULL,
    video_id   BIGINT UNSIGNED NULL,
    blocked_by BIGINT UNSIGNED NULL,
    blocked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
