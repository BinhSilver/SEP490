package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Local compatibility. Shared deployments apply sql/migrations/004_shadowing_subtitle_quality_review.sql. */
@Component
@Profile("local")
@Order(4)
public class SubtitleQualityReviewSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public SubtitleQualityReviewSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS subtitle_quality_review ("
                + "subtitle_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, source_type VARCHAR(32) NOT NULL, "
                + "source_text TEXT NOT NULL, transcription_text TEXT NOT NULL, similarity_score DECIMAL(5,4) NOT NULL, "
                + "review_status VARCHAR(32) NOT NULL, review_reason VARCHAR(500) NULL, reviewed_by_user_id BIGINT UNSIGNED NULL, "
                + "reviewed_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_subtitle_quality_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_subtitle_quality_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE SET NULL, "
                + "INDEX idx_subtitle_quality_status (review_status, subtitle_id), "
                + "INDEX idx_subtitle_quality_reviewer (reviewed_by_user_id, reviewed_at))");
    }
}
