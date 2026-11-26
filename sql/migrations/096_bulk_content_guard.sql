-- Chống tải nội dung hàng loạt (tải xuống / cào dữ liệu và đăng tải hàng loạt): cảnh báo → tạm ngưng → khoá tài khoản.
-- 1) user_bulk_violation: mỗi dòng = một lần tài khoản vượt ngưỡng request trong một cửa sổ thời gian, kèm mức xử lý
--    hệ thống đã áp (WARN / SUSPEND / LOCK). Số lần vi phạm chưa được gỡ (cleared_at IS NULL) trong 30 ngày quyết định
--    mức tiếp theo; admin "gỡ vi phạm" ở trang Chế tài để reset.
-- 2) user_sanction.created_by cho phép NULL: chế tài do hệ thống tự áp (tạm ngưng / khoá vì tải hàng loạt) không có
--    người tạo.
-- Chạy SAU 095_arena_puzzle_games.sql. Chạy lại an toàn.
SET NAMES utf8mb4;

ALTER TABLE user_sanction MODIFY created_by BIGINT UNSIGNED NULL;

CREATE TABLE IF NOT EXISTS user_bulk_violation (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    kind           VARCHAR(10)     NOT NULL,
    request_count  INT UNSIGNED    NOT NULL,
    request_limit  INT UNSIGNED    NOT NULL,
    window_seconds INT UNSIGNED    NOT NULL,
    sample_path    VARCHAR(255)    NULL,
    action_taken   VARCHAR(10)     NOT NULL,
    sanction_id    BIGINT UNSIGNED NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cleared_at     DATETIME(6)     NULL,
    cleared_by     BIGINT UNSIGNED NULL,
    clear_reason   VARCHAR(500)    NULL,
    PRIMARY KEY (id),
    KEY idx_user_bulk_violation_user (user_id, created_at),
    KEY idx_user_bulk_violation_created (created_at),
    CONSTRAINT fk_user_bulk_violation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_bulk_violation_sanction FOREIGN KEY (sanction_id) REFERENCES user_sanction(id) ON DELETE SET NULL,
    CONSTRAINT fk_user_bulk_violation_cleared_by FOREIGN KEY (cleared_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_user_bulk_violation_kind CHECK (kind IN ('DOWNLOAD','UPLOAD')),
    CONSTRAINT chk_user_bulk_violation_action CHECK (action_taken IN ('WARN','SUSPEND','LOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
