-- 095) [JLAS-W0108] Đồng bộ 3 bảng và 4 cột mới trong sql/learning/learning_schema.sql cho DB deploy cũ. Chạy lại an toàn.
--      Bảng: cpath_user_week_miss, learning_pace_week, learning_path_flashcard_applied
--      Cột:  jpath_user_path.extra_skill_day, cpath_item.example_ja / example_furigana / example_vi
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS cpath_user_week_miss (
    path_id BIGINT UNSIGNED NOT NULL,
    item_code VARCHAR(40) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    due_on DATE NOT NULL,
    PRIMARY KEY (path_id, item_code),
    KEY idx_cpath_week_miss_due (path_id, due_on),
    CONSTRAINT chk_cpath_week_miss_kind CHECK (kind IN ('ITEM','LINE')),
    CONSTRAINT fk_cpath_week_miss_path FOREIGN KEY (path_id) REFERENCES cpath_user_path(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_pace_week (
    path_kind VARCHAR(8) NOT NULL,
    path_id BIGINT UNSIGNED NOT NULL,
    week_start DATE NOT NULL,
    first_score TINYINT UNSIGNED NULL,
    decision VARCHAR(8) NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (path_kind, path_id, week_start),
    CONSTRAINT chk_learning_pace_week_kind CHECK (path_kind IN ('JLPT','COMM')),
    CONSTRAINT chk_learning_pace_week_decision CHECK (decision IS NULL OR decision IN ('APPLIED','KEPT','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_path_flashcard_applied (
    user_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'jpath_user_path' AND column_name = 'extra_skill_day');
SET @ddl := IF(@c = 0, 'ALTER TABLE jpath_user_path ADD COLUMN extra_skill_day TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_item' AND column_name = 'example_ja');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_item ADD COLUMN example_ja VARCHAR(255) NULL AFTER note_vi', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_item' AND column_name = 'example_furigana');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_item ADD COLUMN example_furigana VARCHAR(500) NULL AFTER example_ja', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'cpath_item' AND column_name = 'example_vi');
SET @ddl := IF(@c = 0, 'ALTER TABLE cpath_item ADD COLUMN example_vi VARCHAR(500) NULL AFTER example_furigana', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
