-- [JLAS2-37] Community Hub: "Lưu bài" để xem lại sau (giống mục Đã lưu của TikTok).
-- Mỗi dòng = một người dùng đã lưu một mục trên feed Cộng đồng. Lưu theo (content_type, content_id) như
-- community_content nên lưu được mọi loại trên feed: bài viết (POST), bộ flashcard, video Shadowing.
-- Không có khoá ngoại tới bảng nguồn (mỗi loại một bảng khác nhau); nội dung bị gỡ / xoá chỉ không còn hiện ở
-- bộ lọc "Đã lưu" (feed chỉ trả nội dung đang công khai). Chạy SAU 089_community_post_media.sql. Chạy lại an toàn.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS community_saved_post (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    content_type VARCHAR(30)     NOT NULL,
    content_id   BIGINT UNSIGNED NOT NULL,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_saved_post (user_id, content_type, content_id),
    KEY idx_community_saved_post_user (user_id, created_at),
    CONSTRAINT fk_community_saved_post_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
