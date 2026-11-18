-- [TUTOR] Từ trái nghĩa do gia sư AI gợi ý khi vocabulary_antonym chưa có: model đề xuất, hệ thống chỉ giữ từ có mục
-- trong từ điển J-LAS, rồi lưu ở đây để mọi người hỏi sau nhận cùng một đáp án và để người duyệt rà lại.
--   status: PENDING  = chưa duyệt, hiển thị kèm dòng "gợi ý của AI, chưa được người duyệt"
--           APPROVED = đã duyệt, hiển thị như dữ liệu thường
--           REJECTED = gợi ý sai, gia sư trả lời "chưa có"
--   antonym_vocabulary_id NULL = model không đề xuất được từ nào kiểm chứng được (người duyệt có thể điền tay rồi APPROVED).
-- Không có entity nên Hibernate validate không đụng tới; thiếu bảng thì gia sư vẫn chạy, chỉ không lưu. Chạy lại an toàn.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tutor_antonym_suggestion (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id         BIGINT UNSIGNED NOT NULL,
    antonym_vocabulary_id BIGINT UNSIGNED NULL,
    status                VARCHAR(12)     NOT NULL DEFAULT 'PENDING',
    model                 VARCHAR(80)     NULL,
    ask_count             INT UNSIGNED    NOT NULL DEFAULT 1,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tutor_antonym_vocabulary (vocabulary_id),
    KEY idx_tutor_antonym_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
