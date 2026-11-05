package com.jlas.game.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tạo bảng của Cửa hàng Trò chơi (đã có trong jlas_schema.sql) cho DB chưa chạy migration đó. Hai bảng chỉ truy cập
 * qua JDBC nên kiểm tra schema của JPA không phụ thuộc vào chúng.
 */
@Component
@Profile("!test")
@Order(1)
public class GameShopSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public GameShopSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS game_shop_skin ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, "
                + "game_type VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(500) NOT NULL DEFAULT '', "
                + "price INT NOT NULL DEFAULT 0, image VARCHAR(1024) NOT NULL, render_code VARCHAR(40) NOT NULL, "
                + "starter BOOLEAN NOT NULL DEFAULT FALSE, active BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE, "
                + "CONSTRAINT chk_game_shop_skin_price CHECK (price >= 0)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        // Migration 055: gói skin tải lên (pack_path) và bộ vũ khí máy bay mượn (weapon_theme).
        // Migration 066: bậc trang phục (Thường / Hiếm / Huyền thoại), quyết định giá.
        boolean tierAdded = false;
        for (String column : new String[]{"pack_path VARCHAR(255) NULL", "weapon_theme VARCHAR(40) NULL",
                "tier VARCHAR(20) NOT NULL DEFAULT 'COMMON'"}) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'game_shop_skin' AND COLUMN_NAME = ?", Integer.class, column.substring(0, column.indexOf(' ')));
            if (exists == null || exists == 0) {
                jdbc.execute("ALTER TABLE game_shop_skin ADD COLUMN " + column);
                tierAdded |= column.startsWith("tier ");
            }
        }
        if (tierAdded) backfillTiers();
        for (var game : com.jlas.game.util.GameShopCatalog.SHOP_GAMES) {
            for (var item : com.jlas.game.util.GameShopCatalog.items(game)) {
                jdbc.update("INSERT IGNORE INTO game_shop_skin (id, code, game_type, name, description, price, image, render_code, starter, tier) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        item.id(), item.code(), game.name(), item.name(), item.description(), item.price(), item.image(), item.code(), item.starter(),
                        item.tier().name());
            }
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_game_item ("
                + "user_id BIGINT UNSIGNED NOT NULL, item_code VARCHAR(40) NOT NULL, "
                + "price_paid INT NOT NULL DEFAULT 0, "
                + "purchased_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "PRIMARY KEY (user_id, item_code), "
                + "CONSTRAINT fk_user_game_item_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_user_game_item_price CHECK (price_paid >= 0)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_game_loadout ("
                + "user_id BIGINT UNSIGNED NOT NULL, game_type VARCHAR(20) NOT NULL, item_code VARCHAR(40) NOT NULL, "
                + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "PRIMARY KEY (user_id, game_type), "
                + "CONSTRAINT fk_user_game_loadout_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    /**
     * Same as migration 066, run once when the tier column is first added: built-in skins still at their old price
     * take their catalog tier, every other skin the tier nearest its price, and the price follows the tier.
     */
    private void backfillTiers() {
        for (var game : com.jlas.game.util.GameShopCatalog.SHOP_GAMES) {
            for (var item : com.jlas.game.util.GameShopCatalog.items(game)) {
                if (item.starter()) continue;
                jdbc.update("UPDATE game_shop_skin SET tier = ?, price = ? WHERE code = ? AND starter = FALSE AND price IN (150, 200)",
                        item.tier().name(), item.price(), item.code());
            }
        }
        jdbc.update("UPDATE game_shop_skin SET tier = CASE WHEN price >= 1000 THEN 'LEGENDARY' WHEN price >= 800 THEN 'RARE' ELSE 'COMMON' END, "
                + "price = CASE WHEN price >= 1000 THEN 1000 WHEN price >= 800 THEN 800 ELSE 500 END "
                + "WHERE starter = FALSE AND price NOT IN (500, 800, 1000)");
        jdbc.update("UPDATE game_shop_skin SET tier = CASE price WHEN 1000 THEN 'LEGENDARY' WHEN 800 THEN 'RARE' ELSE 'COMMON' END "
                + "WHERE starter = FALSE AND price IN (500, 800, 1000)");
    }
}
