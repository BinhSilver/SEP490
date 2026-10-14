-- [JLAS-W0156] Flashcard SRS: nhật ký từng lần chấm thẻ (heatmap, tỉ lệ nhớ, số lượt ôn mỗi ngày).
-- Lịch ôn vẫn nằm ở flashcard_card_progress; bảng này chỉ thêm, không sửa dữ liệu cũ. Safe to rerun.
-- Không đặt FK tới flashcard_card: sửa bộ thẻ có thể thay thẻ mới nhưng lịch sử học của người dùng vẫn giữ.
CREATE TABLE IF NOT EXISTS flashcard_review_log (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT UNSIGNED NOT NULL,
    card_id                BIGINT UNSIGNED NOT NULL,
    set_id                 BIGINT UNSIGNED NULL,
    quality                TINYINT UNSIGNED NOT NULL,
    previous_interval_days INT UNSIGNED NOT NULL DEFAULT 0,
    interval_days          INT UNSIGNED NOT NULL DEFAULT 0,
    scheduled              BOOLEAN NOT NULL DEFAULT TRUE,
    source                 VARCHAR(20) NOT NULL,
    reviewed_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_flashcard_review_log_user_time (user_id, reviewed_at),
    CONSTRAINT fk_flashcard_review_log_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_flashcard_review_log_quality CHECK (quality <= 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
