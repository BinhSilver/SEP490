package com.jlas.game.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tạo bảng khung avatar (migration 031) cho DB chưa chạy migration đó. Hai bảng chỉ truy cập qua JDBC nên
 * kiểm tra schema của JPA không phụ thuộc vào chúng.
 */
@Component
@Profile("!test")
@Order(1)
public class AvatarFrameSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public AvatarFrameSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_avatar_frame ("
                + "user_id BIGINT UNSIGNED NOT NULL, frame_code VARCHAR(40) NOT NULL, "
                + "price_paid INT NOT NULL DEFAULT 0, "
                + "purchased_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "PRIMARY KEY (user_id, frame_code), "
                + "CONSTRAINT fk_user_avatar_frame_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_user_avatar_frame_price CHECK (price_paid >= 0)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_profile_style ("
                + "user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, frame_code VARCHAR(40) NULL, name_style VARCHAR(40) NULL, "
                + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_user_profile_style_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        // Migration 042: danh mục khung do admin quản lý; nạp sẵn 4 khung gốc, không ghi đè chỉnh sửa của admin.
        jdbc.execute("CREATE TABLE IF NOT EXISTS avatar_frame ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, "
                + "name VARCHAR(100) NOT NULL, description VARCHAR(500) NOT NULL DEFAULT '', price INT NOT NULL DEFAULT 0, "
                + "image VARCHAR(1024) NOT NULL, theme VARCHAR(20) NOT NULL, name_style VARCHAR(40) NOT NULL, "
                + "name_style_label VARCHAR(60) NOT NULL, gems_json TEXT NULL, sort_order INT NOT NULL DEFAULT 0, "
                + "active BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE, "
                + "CONSTRAINT chk_avatar_frame_price CHECK (price >= 0)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        for (var frame : com.jlas.game.util.AvatarFrameCatalog.seeds()) {
            jdbc.update("INSERT IGNORE INTO avatar_frame (id, code, name, description, price, image, theme, name_style, "
                            + "name_style_label, gems_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    frame.id(), frame.code(), frame.name(), frame.description(), frame.price(), frame.image(),
                    frame.theme(), frame.nameStyle(), frame.nameStyleLabel(), frame.gemsJson(), frame.sortOrder());
        }
    }
}
