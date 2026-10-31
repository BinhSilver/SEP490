package com.jlas.learning.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies migration 057 (integrity events of timed quiz attempts) for databases that have not run it. */
@Component
@Profile("!test")
@Order(1)
public class QuizIntegritySchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public QuizIntegritySchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS assessment_attempt_integrity_event ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, attempt_id BIGINT UNSIGNED NOT NULL, "
                + "event_type VARCHAR(30) NOT NULL, detail VARCHAR(255) NULL, "
                + "occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY (id), "
                + "KEY idx_attempt_integrity_attempt (attempt_id, occurred_at), "
                + "CONSTRAINT fk_attempt_integrity_attempt FOREIGN KEY (attempt_id) REFERENCES assessment_attempt(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_attempt_integrity_type CHECK (event_type IN "
                + "('TAB_HIDDEN','WINDOW_BLUR','FULLSCREEN_EXIT','MULTI_TAB','RESUME',"
                + "'COPY','PASTE','CONTEXT_MENU','DEVTOOLS','TIME_UP','AUTO_SUBMIT'))"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
