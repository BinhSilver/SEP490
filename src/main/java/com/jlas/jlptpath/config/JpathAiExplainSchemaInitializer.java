package com.jlas.jlptpath.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] F36: Tự tạo các bảng ví lượt, hạn mức ngày Pro và bộ nhớ đệm cho AI giải thích câu hỏi Learning
 * nếu cơ sở dữ liệu chưa chạy lại {@code sql/learning/learning_schema.sql}.
 */
@Component
@Profile("!test")
@Order(2)
public class JpathAiExplainSchemaInitializer implements ApplicationRunner {
    private static final String TABLE_OPTIONS = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private final JdbcTemplate jdbc;

    public JpathAiExplainSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_ai_explain_wallet ("
                + "user_id BIGINT UNSIGNED NOT NULL, remaining INT UNSIGNED NOT NULL DEFAULT 0, "
                + "trial_used TINYINT(1) NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL, "
                + "PRIMARY KEY (user_id), "
                + "CONSTRAINT fk_jpath_ai_wallet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_ai_explain_daily ("
                + "user_id BIGINT UNSIGNED NOT NULL, usage_date DATE NOT NULL, "
                + "pro_used INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (user_id, usage_date), "
                + "CONSTRAINT fk_jpath_ai_daily_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_ai_explain_seen ("
                + "user_id BIGINT UNSIGNED NOT NULL, usage_date DATE NOT NULL, cache_key CHAR(64) NOT NULL, "
                + "PRIMARY KEY (user_id, usage_date, cache_key), "
                + "CONSTRAINT fk_jpath_ai_seen_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                + TABLE_OPTIONS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_ai_explain_cache ("
                + "cache_key CHAR(64) NOT NULL, source VARCHAR(30) NOT NULL, answer_json MEDIUMTEXT NOT NULL, "
                + "model VARCHAR(120) NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY (cache_key)"
                + TABLE_OPTIONS);
    }
}
