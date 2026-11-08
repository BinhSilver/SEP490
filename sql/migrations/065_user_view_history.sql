-- [JLAS-VIEW-HISTORY] "Lịch sử" kiểu YouTube cho Flashcard và Shadowing: lần gần nhất người dùng mở một bộ thẻ
-- hoặc một video. Một dòng mỗi (người dùng, mục); mở lại chỉ đổi viewed_at. item_id không có khoá ngoại vì trỏ tới
-- hai bảng (flashcard_set / video); mục đã xoá chỉ bị bỏ qua khi hiển thị. Chạy lại an toàn;
-- ViewHistorySchemaInitializer cũng tự tạo bảng khi khởi động.
CREATE TABLE IF NOT EXISTS user_view_history (
    user_id   BIGINT UNSIGNED NOT NULL,
    item_type VARCHAR(30) NOT NULL,     -- FLASHCARD_SET | SHADOWING_VIDEO
    item_id   BIGINT UNSIGNED NOT NULL,
    viewed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, item_type, item_id),
    KEY idx_user_view_history_recent (user_id, item_type, viewed_at),
    CONSTRAINT fk_user_view_history_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_view_history_type CHECK (item_type IN ('FLASHCARD_SET', 'SHADOWING_VIDEO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
