-- [JLAS-XXXX] Khung avatar + màu tên: món trang trí hồ sơ bán trong Cửa hàng bằng CP chung.
-- Danh mục khung nằm trong code (AvatarFrameCatalog); DB chỉ lưu khung đã mua và lựa chọn đang dùng. Safe to rerun.
-- Mua một khung là mở luôn màu tên đi kèm, nên không có bảng riêng cho màu tên.
CREATE TABLE IF NOT EXISTS user_avatar_frame (
    user_id      BIGINT UNSIGNED NOT NULL,
    frame_code   VARCHAR(40) NOT NULL,
    price_paid   INT NOT NULL DEFAULT 0,
    purchased_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, frame_code),
    CONSTRAINT fk_user_avatar_frame_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_avatar_frame_price CHECK (price_paid >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- NULL = không dùng khung / màu tên mặc định.
CREATE TABLE IF NOT EXISTS user_profile_style (
    user_id    BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    frame_code VARCHAR(40) NULL,
    name_style VARCHAR(40) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_user_profile_style_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
