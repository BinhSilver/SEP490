package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("legacy-schema")
@Order(0)
public class DatabaseSchemaFixer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        widenProcessingStepColumn();
        allowNullVideoPath();
    }

    private void widenProcessingStepColumn() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE video MODIFY COLUMN processing_step VARCHAR(32) NULL");
            System.out.println("[Schema] widened video.processing_step to VARCHAR(32)");
        } catch (Exception e) {
            System.err.println("[Schema] could not widen processing_step: " + e.getMessage());
        }
    }

    /**
     * YouTube import inserts a PROCESSING row before download finishes — video_path is filled later.
     */
    private void allowNullVideoPath() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE video MODIFY COLUMN video_path TEXT NULL");
            System.out.println("[Schema] video.video_path is now NULL-able (YouTube import)");
        } catch (Exception e) {
            System.err.println("[Schema] could not alter video_path: " + e.getMessage());
        }
    }

}
