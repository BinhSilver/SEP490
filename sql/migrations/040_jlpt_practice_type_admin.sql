-- Run once in an existing JLAS database before using /admin/jlpt/practice-types.
-- Question counts/content remain in jlpt_* tables; this table stores only admin overrides.
CREATE TABLE IF NOT EXISTS jlpt_practice_type_config (
    level_code VARCHAR(2) NOT NULL,
    type_code VARCHAR(50) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    name_vi VARCHAR(120) NULL,
    name_ja VARCHAR(120) NULL,
    order_index INT UNSIGNED NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by BIGINT UNSIGNED NULL,
    PRIMARY KEY (level_code, type_code),
    KEY idx_jlpt_practice_type_enabled_order (level_code, is_enabled, order_index),
    CONSTRAINT chk_jlpt_practice_type_level CHECK (level_code IN ('N1','N2','N3','N4','N5')),
    CONSTRAINT fk_jlpt_practice_type_updated_by FOREIGN KEY (updated_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
