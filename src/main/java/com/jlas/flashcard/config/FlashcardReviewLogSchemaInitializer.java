package com.jlas.flashcard.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tạo bảng nhật ký ôn flashcard (migration 030) cho DB chưa chạy migration đó. Bảng chỉ truy cập qua JDBC
 * nên kiểm tra schema của JPA không phụ thuộc vào nó.
 */
@Component
@Profile("!test")
@Order(1)
public class FlashcardReviewLogSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public FlashcardReviewLogSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS flashcard_review_log ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, "
                + "card_id BIGINT UNSIGNED NOT NULL, set_id BIGINT UNSIGNED NULL, quality TINYINT UNSIGNED NOT NULL, "
                + "previous_interval_days INT UNSIGNED NOT NULL DEFAULT 0, interval_days INT UNSIGNED NOT NULL DEFAULT 0, "
                + "scheduled BOOLEAN NOT NULL DEFAULT TRUE, source VARCHAR(20) NOT NULL, "
                + "reviewed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "KEY idx_flashcard_review_log_user_time (user_id, reviewed_at), "
                + "CONSTRAINT fk_flashcard_review_log_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_flashcard_review_log_quality CHECK (quality <= 5)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
