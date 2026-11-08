package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies migration 056 (comment replies and reactions) for databases that have not run it. */
@Component
@Profile("!test")
@Order(1)
public class ShadowingCommentThreadSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ShadowingCommentThreadSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        Integer hasParent = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_comment' AND COLUMN_NAME = 'parent_id'", Integer.class);
        if (hasParent == null || hasParent == 0) {
            jdbc.execute("ALTER TABLE content_comment ADD COLUMN parent_id BIGINT UNSIGNED NULL AFTER target_id, "
                    + "ADD KEY idx_content_comment_parent (parent_id, created_at), "
                    + "ADD CONSTRAINT fk_content_comment_parent FOREIGN KEY (parent_id) REFERENCES content_comment(id) ON DELETE CASCADE");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS content_comment_reaction ("
                + "comment_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, reaction VARCHAR(16) NOT NULL, "
                + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY (comment_id, user_id), "
                + "KEY idx_comment_reaction_user (user_id), "
                + "CONSTRAINT fk_comment_reaction_comment FOREIGN KEY (comment_id) REFERENCES content_comment(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_comment_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT chk_comment_reaction CHECK (reaction IN ('LIKE','LOVE','HAHA','WOW','SAD','CLAP'))"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
