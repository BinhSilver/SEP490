package com.jlas.game.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tạo bảng của Cửa hàng Trò chơi (sql/migrations/009) cho DB chưa chạy migration đó. Hai bảng chỉ truy cập
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
