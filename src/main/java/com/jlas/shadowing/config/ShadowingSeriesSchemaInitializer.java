package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates the small playlist tables for installations that have not run the migration yet. */
@Component
@Profile("!test")
@Order(1)
public class ShadowingSeriesSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ShadowingSeriesSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_series ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, "
                + "title VARCHAR(255) NOT NULL, description TEXT NULL, estimated_level VARCHAR(16) NULL, "
                + "level_source VARCHAR(16) NULL, visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE', "
                + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "INDEX idx_shadowing_series_public (visibility, created_at), INDEX idx_shadowing_series_owner (owner_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_series_video ("
                + "series_id BIGINT NOT NULL, video_id BIGINT NOT NULL, position INT NOT NULL, "
                + "PRIMARY KEY (series_id, video_id), INDEX idx_shadowing_series_video_position (series_id, position))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS content_comment_rating ("
                + "comment_id BIGINT NOT NULL PRIMARY KEY, rating TINYINT NOT NULL, "
                + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
    }
}
