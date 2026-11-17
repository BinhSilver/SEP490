-- [TUTOR] Câu trả lời "tầng 2" của gia sư AI: khi tài liệu J-LAS không có nội dung cho một câu hỏi tiếng Nhật, AI tổng hợp
-- câu trả lời, gắn nhãn "chưa có trong tài liệu J-LAS", và lưu ở đây để người hỏi sau nhận cùng bản đó (không gọi lại AI)
-- và để người duyệt rà lại.
--   question_hash: SHA-256 của câu hỏi đã chuẩn hóa (NFKC, chữ thường, gộp khoảng trắng, bỏ dấu câu cuối).
--   status: PENDING  = chưa duyệt, hiển thị kèm nhãn "AI tổng hợp, chưa được người duyệt"
--           APPROVED = đã duyệt, hiển thị kèm nhãn "AI tổng hợp, đã được người duyệt"
--           REJECTED = câu trả lời sai, gia sư quay về "chưa có nội dung" cho câu hỏi này
-- Người duyệt có thể sửa thẳng cột answer rồi đổi status. Không có entity; thiếu bảng thì gia sư vẫn trả lời, chỉ không lưu.
-- Chạy lại an toàn.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tutor_ai_answer (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_hash CHAR(64)        NOT NULL,
    question      VARCHAR(600)    NOT NULL,
    answer        TEXT            NOT NULL,
    status        VARCHAR(12)     NOT NULL DEFAULT 'PENDING',
    model         VARCHAR(120)    NULL,
    ask_count     INT UNSIGNED    NOT NULL DEFAULT 1,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tutor_ai_answer_question (question_hash),
    KEY idx_tutor_ai_answer_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
