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
        for (var game : com.jlas.game.util.GameShopCatalog.SHOP_GAMES) {
            for (var item : com.jlas.game.util.GameShopCatalog.items(game)) {
                jdbc.update("INSERT IGNORE INTO game_shop_skin (id, code, game_type, name, description, price, image, render_code, starter) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        item.id(), item.code(), game.name(), item.name(), item.description(), item.price(), item.image(), item.code(), item.starter());
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
}
