package com.jlas.flashcard.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Thêm cột thời gian mỗi từ của Gõ phản xạ (migration 074) cho DB chưa chạy migration đó. Cột chỉ truy cập qua
 * JDBC (SpeedRecallTimeLimitRepository) nên kiểm tra schema của JPA không phụ thuộc vào nó.
 */
@Component
@Profile("!test")
@Order(1)
public class SpeedRecallTimeLimitSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SpeedRecallTimeLimitSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'flashcard_speed_recall_session' AND COLUMN_NAME = 'time_limit_seconds'", Integer.class);
        if (exists == null || exists == 0) {
            jdbc.execute("ALTER TABLE flashcard_speed_recall_session ADD COLUMN time_limit_seconds TINYINT UNSIGNED NULL");
        }
    }
}
