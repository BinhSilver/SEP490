package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies migration 012 (view counter, listening tracker, milestone rewards) for databases that have not run it. */
@Component
@Profile("!test")
@Order(1)
public class ShadowingViewSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ShadowingViewSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        Integer hasViewCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'video' AND COLUMN_NAME = 'view_count'", Integer.class);
        if (hasViewCount == null || hasViewCount == 0) {
            jdbc.execute("ALTER TABLE video ADD COLUMN view_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER moderation_summary");
            // Carry over views counted under the old one-view-per-account rule.
            jdbc.update("UPDATE video v JOIN (SELECT video_id, COUNT(*) AS views FROM video_user_state "
                    + "WHERE counted_as_view = 1 GROUP BY video_id) s ON s.video_id = v.id SET v.view_count = s.views");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS video_view_tracker ("
                + "user_id BIGINT UNSIGNED NOT NULL, video_id BIGINT UNSIGNED NOT NULL, "
                + "listened_sec DECIMAL(10,3) NOT NULL DEFAULT 0, last_ping_at DATETIME(6) NULL, last_counted_at DATETIME(6) NULL, "
                + "PRIMARY KEY (user_id, video_id), KEY idx_video_view_tracker_video (video_id), "
                + "CONSTRAINT fk_video_view_tracker_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_video_view_tracker_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        // [JLAS-SHADOWING-POPULAR] Same additive, rerunnable DDL as migration 026.
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_view_event ("
                + "user_id BIGINT UNSIGNED NOT NULL, video_id BIGINT UNSIGNED NOT NULL, counted_at DATETIME(6) NOT NULL, "
                + "PRIMARY KEY (user_id, video_id, counted_at), KEY idx_shadowing_view_event_recent (counted_at, video_id), "
                + "KEY idx_shadowing_view_event_video (video_id), "
                + "CONSTRAINT fk_shadowing_view_event_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_shadowing_view_event_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.update("INSERT IGNORE INTO shadowing_view_event (user_id, video_id, counted_at) "
                + "SELECT user_id, video_id, last_counted_at FROM video_view_tracker "
                + "WHERE last_counted_at >= CURRENT_TIMESTAMP(6) - INTERVAL 7 DAY AND last_counted_at <= CURRENT_TIMESTAMP(6)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_view_milestone ("
                + "video_id BIGINT UNSIGNED NOT NULL, milestone INT UNSIGNED NOT NULL, "
                + "owner_id BIGINT UNSIGNED NOT NULL, credit INT UNSIGNED NOT NULL, "
                + "reached_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY (video_id, milestone), "
                + "CONSTRAINT fk_shadowing_view_milestone_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }
}
