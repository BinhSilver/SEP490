-- Cửa hàng Trò chơi: trang phục mèo (Bắt cá) và máy bay (Bắn máy bay), mua bằng CP chung (users.credit_balance).
-- Danh mục món nằm trong code (GameShopCatalog); hai bảng này chỉ lưu món đã mua và món đang dùng.
-- Món thuộc game nào suy ra từ item_code qua GameShopCatalog nên user_game_item không lưu game_type (3NF).
-- Giao dịch CP ghi vào user_credit_transaction với reason GAME_ITEM_PURCHASE, reference GAME_ITEM + id món.
-- App tự tạo hai bảng này khi khởi động (GameShopSchemaInitializer); file này dành cho DB quản lý bằng tay. MySQL 8+.
CREATE TABLE IF NOT EXISTS user_game_item (
    user_id      BIGINT UNSIGNED NOT NULL,
    item_code    VARCHAR(40) NOT NULL,
    price_paid   INT NOT NULL DEFAULT 0,
    purchased_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, item_code),
    CONSTRAINT fk_user_game_item_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_game_item_price CHECK (price_paid >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_game_loadout (
    user_id    BIGINT UNSIGNED NOT NULL,
    game_type  VARCHAR(20) NOT NULL,
    item_code  VARCHAR(40) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
               ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, game_type),
    CONSTRAINT fk_user_game_loadout_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
