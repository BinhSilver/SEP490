-- [JLAS-XXXX] Admin CRUD skin. Re-runnable on existing databases.
-- Current built-in skins are seeded without overwriting admin changes by GameShopSchemaInitializer.
CREATE TABLE IF NOT EXISTS game_shop_skin (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    game_type VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    price INT NOT NULL DEFAULT 0,
    image VARCHAR(1024) NOT NULL,
    render_code VARCHAR(40) NOT NULL,
    starter BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_game_shop_skin_price CHECK (price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
