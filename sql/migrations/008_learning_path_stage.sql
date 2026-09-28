-- [JLAS-W0108] P6 — chặng nhiều cấp (D5). Thêm bảng user_learning_stage + cột stage_id trên
-- user_learning_path_item để gán từng activity về chặng mục tiêu của nó. Path một cấp (N5→N4) vẫn
-- có đúng một chặng; item cũ giữ stage_id NULL (tương thích ngược, không phá lịch sử). Không DROP dữ liệu.

ALTER TABLE user_learning_path_item
    ADD COLUMN stage_id BIGINT UNSIGNED NULL;

CREATE TABLE user_learning_stage (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id  BIGINT UNSIGNED NOT NULL,
    level             VARCHAR(10) NOT NULL,
    stage_index       INT UNSIGNED NOT NULL,
    curriculum_version VARCHAR(100) NULL,
    assessment_cycle  VARCHAR(50) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_stage_order (learning_path_id, stage_index),
    KEY idx_learning_stage_path (learning_path_id),
    CONSTRAINT fk_learning_stage_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_stage_level
        CHECK (level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_learning_stage_status CHECK (status IN ('PENDING','ACTIVE','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
