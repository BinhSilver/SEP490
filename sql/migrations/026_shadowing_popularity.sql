-- [JLAS-SHADOWING-POPULAR] Valid view history for the rolling seven-day popularity score.
-- Keep the accepted-view timestamp; refreshing a page never creates an event.
CREATE TABLE IF NOT EXISTS shadowing_view_event (
    user_id    BIGINT UNSIGNED NOT NULL,
    video_id   BIGINT UNSIGNED NOT NULL,
    counted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, video_id, counted_at),
    KEY idx_shadowing_view_event_recent (counted_at, video_id),
    KEY idx_shadowing_view_event_video (video_id),
    CONSTRAINT fk_shadowing_view_event_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_view_event_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Recover only the last accepted view actually known by the old tracker.
-- Older repeated views cannot be reconstructed. Do not infer them from last_watched_at.
INSERT IGNORE INTO shadowing_view_event (user_id, video_id, counted_at)
SELECT user_id, video_id, last_counted_at FROM video_view_tracker
WHERE last_counted_at >= CURRENT_TIMESTAMP(6) - INTERVAL 7 DAY
  AND last_counted_at <= CURRENT_TIMESTAMP(6);
