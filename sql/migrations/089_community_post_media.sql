-- [JLAS2-37] Community Hub: ảnh / video đính kèm bài viết (community_post).
--   kind IMAGE   : ảnh đã thu nhỏ (cạnh dài ≤ 1600px), lưu trong container media của Azure Blob; storage_key = tên blob.
--   kind VIDEO   : video người dùng tải lên (MP4/WebM ≤ 50MB), cũng nằm trên Azure Blob; storage_key = tên blob.
--   kind YOUTUBE : video YouTube nhúng; storage_key = mã video 11 ký tự (không lưu tệp).
-- Mỗi bài tối đa 4 ảnh + 1 video (tải lên HOẶC YouTube). Trình duyệt nhận link đọc có chữ ký (không public container).
-- Xoá bài là xoá mềm (community_post.deleted_at) nên dòng media giữ nguyên; ON DELETE CASCADE chỉ cho xoá cứng.
-- Chạy SAU 087_community_post.sql. Chạy lại an toàn.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS community_post_media (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    post_id      BIGINT UNSIGNED NOT NULL,
    kind         VARCHAR(10)     NOT NULL,
    storage_key  VARCHAR(300)    NOT NULL,
    content_type VARCHAR(60)     NULL,
    size_bytes   BIGINT UNSIGNED NULL,
    sort_order   INT             NOT NULL DEFAULT 0,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_community_post_media_post (post_id, sort_order),
    CONSTRAINT fk_community_post_media_post FOREIGN KEY (post_id) REFERENCES community_post(id) ON DELETE CASCADE,
    CONSTRAINT chk_community_post_media_kind CHECK (kind IN ('IMAGE', 'VIDEO', 'YOUTUBE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
