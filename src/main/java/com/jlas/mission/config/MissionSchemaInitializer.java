package com.jlas.mission.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the mission tables from sql/jlas_migrations_all.sql for installations that have not run it yet.
 * These tables are accessed through JDBC only, so JPA schema validation does not depend on them.
 */
@Component
@Profile("!test")
@Order(1)
public class MissionSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public MissionSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_event_receipt ("
                + "event_id VARCHAR(120) NOT NULL PRIMARY KEY, "
                + "user_id BIGINT UNSIGNED NOT NULL, event_type VARCHAR(40) NOT NULL, "
                + "occurred_at DATETIME(6) NOT NULL, "
                + "processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "INDEX idx_event_receipt_user (user_id, processed_at), "
                + "CONSTRAINT fk_event_receipt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_time_zone ("
                + "user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, zone_id VARCHAR(64) NOT NULL, "
                + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_user_time_zone_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pending_credit_award ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT UNSIGNED NOT NULL, amount INT NOT NULL, reason VARCHAR(30) NOT NULL, "
                + "reference_type VARCHAR(30) NOT NULL, reference_id BIGINT UNSIGNED NOT NULL, "
                + "note VARCHAR(200) NULL, celebration_kind VARCHAR(30) NULL, celebration_title VARCHAR(160) NULL, "
                + "streak INT NULL, attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(500) NULL, "
                + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "completed_at DATETIME(6) NULL, "
                + "UNIQUE KEY uk_pending_award_reference (user_id, reason, reference_type, reference_id), "
                + "INDEX idx_pending_award_due (completed_at, next_attempt_at), "
                + "CONSTRAINT fk_pending_award_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_pending_award_amount CHECK (amount > 0)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS reward_celebration ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT UNSIGNED NOT NULL, event_key VARCHAR(160) NOT NULL, kind VARCHAR(30) NOT NULL, "
                + "title VARCHAR(160) NOT NULL, amount INT NOT NULL DEFAULT 0, streak INT NULL, "
                + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), seen_at DATETIME(6) NULL, "
                + "UNIQUE KEY uk_reward_celebration_event (user_id, event_key), "
                + "INDEX idx_reward_celebration_unseen (user_id, seen_at, id), "
                + "CONSTRAINT fk_reward_celebration_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS streak_freeze_day ("
                + "user_id BIGINT UNSIGNED NOT NULL, freeze_date DATE NOT NULL, status VARCHAR(20) NOT NULL, "
                + "streak_at_risk INT NOT NULL DEFAULT 0, source VARCHAR(20) NULL, "
                + "offered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), decided_at DATETIME(6) NULL, "
                + "PRIMARY KEY (user_id, freeze_date), "
                + "CONSTRAINT fk_streak_freeze_day_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_streak_freeze_day_status CHECK (status IN ('OFFERED','USED','DECLINED','EXPIRED'))"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
