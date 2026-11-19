package com.jlas.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies the tables of migrations 066 and 079 (gói Pro và CP) for databases that have not run them: quick lookup
 * allowance, daily pronunciation checks, the AI usage log, weekly Shadowing video creation and "Không hỏi lại hôm nay".
 * The reward and price updates stay in the migration.
 */
@Component
@Profile("!test")
@Order(1)
public class ProCpPlanSchemaInitializer implements ApplicationRunner {
    private static final String TABLE_OPTIONS = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private final JdbcTemplate jdbc;

    public ProCpPlanSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS quick_lookup_wallet ("
                + "user_id BIGINT UNSIGNED NOT NULL, remaining INT UNSIGNED NOT NULL DEFAULT 0, "
                + "updated_at DATETIME(6) NOT NULL, PRIMARY KEY (user_id), "
                + "CONSTRAINT fk_quick_lookup_wallet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS quick_lookup_daily ("
                + "user_id BIGINT UNSIGNED NOT NULL, lookup_date DATE NOT NULL, vocabulary_id BIGINT UNSIGNED NOT NULL, "
                + "PRIMARY KEY (user_id, lookup_date, vocabulary_id), "
                + "CONSTRAINT fk_quick_lookup_daily_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS pronunciation_usage_daily ("
                + "user_id BIGINT UNSIGNED NOT NULL, usage_date DATE NOT NULL, attempts INT UNSIGNED NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (user_id, usage_date), "
                + "CONSTRAINT fk_pronunciation_usage_daily_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_usage_log ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, user_id BIGINT UNSIGNED NULL, feature VARCHAR(40) NOT NULL, "
                + "provider VARCHAR(40) NOT NULL, model VARCHAR(120) NULL, prompt_tokens INT UNSIGNED NULL, "
                + "completion_tokens INT UNSIGNED NULL, reasoning_tokens INT UNSIGNED NULL, cached_tokens INT UNSIGNED NULL, "
                + "characters INT UNSIGNED NULL, audio_seconds DECIMAL(8,2) NULL, cost_usd DECIMAL(14,8) NULL, "
                + "created_at DATETIME(6) NOT NULL, PRIMARY KEY (id), "
                + "KEY idx_ai_usage_feature_created (feature, created_at), KEY idx_ai_usage_user_created (user_id, created_at), "
                + "CONSTRAINT fk_ai_usage_log_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_minutes_weekly ("
                + "user_id BIGINT UNSIGNED NOT NULL, week_start DATE NOT NULL, minutes_used INT UNSIGNED NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (user_id, week_start), "
                + "CONSTRAINT fk_shadowing_minutes_weekly_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_create_charge ("
                + "video_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, week_start DATE NOT NULL, "
                + "quota_minutes INT UNSIGNED NOT NULL, credit_paid INT UNSIGNED NOT NULL, refunded_at DATETIME(6) NULL, "
                + "created_at DATETIME(6) NOT NULL, PRIMARY KEY (video_id), "
                + "KEY idx_shadowing_create_charge_user (user_id, created_at), "
                + "CONSTRAINT fk_shadowing_create_charge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS cp_auto_pay ("
                + "user_id BIGINT UNSIGNED NOT NULL, feature VARCHAR(30) NOT NULL, pay_date DATE NOT NULL, "
                + "unit_cost INT UNSIGNED NOT NULL, enabled_at DATETIME(6) NOT NULL, PRIMARY KEY (user_id, feature, pay_date), "
                + "CONSTRAINT fk_cp_auto_pay_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
    }
}
