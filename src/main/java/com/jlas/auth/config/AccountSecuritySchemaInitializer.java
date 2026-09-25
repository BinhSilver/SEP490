package com.jlas.auth.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the email-change challenge table for installations awaiting the SQL migration. */
@Component
@Profile("!test")
@Order(1)
public class AccountSecuritySchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public AccountSecuritySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS email_change_requests ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT UNSIGNED NOT NULL, new_email VARCHAR(255) NOT NULL, "
                + "old_otp_hash CHAR(64) NOT NULL, new_otp_hash CHAR(64) NOT NULL, "
                + "attempt_count INT UNSIGNED NOT NULL DEFAULT 0, "
                + "expires_at DATETIME(6) NOT NULL, confirmed_at DATETIME(6) NULL, "
                + "revoked_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "INDEX idx_email_change_user_active (user_id, confirmed_at, revoked_at, created_at), "
                + "INDEX idx_email_change_expiry (expires_at), "
                + "CONSTRAINT fk_email_change_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");
    }
}
