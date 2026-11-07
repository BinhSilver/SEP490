package com.jlas.common.history;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies migration 065 (user_view_history) for databases that have not run it. */
@Component
@Profile("!test")
@Order(1)
public class ViewHistorySchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ViewHistorySchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_view_history ("
                + "user_id BIGINT UNSIGNED NOT NULL, item_type VARCHAR(30) NOT NULL, item_id BIGINT UNSIGNED NOT NULL, "
                + "viewed_at DATETIME(6) NOT NULL, PRIMARY KEY (user_id, item_type, item_id), "
                + "KEY idx_user_view_history_recent (user_id, item_type, viewed_at), "
                + "CONSTRAINT fk_user_view_history_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_user_view_history_type CHECK (item_type IN ('FLASHCARD_SET', 'SHADOWING_VIDEO'))"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
