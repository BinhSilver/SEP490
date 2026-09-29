package com.jlas.shadowing.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** View counter (video.view_count), per-viewer listening progress and rewarded view milestones. */
@Repository
@RequiredArgsConstructor
public class VideoViewRepository {
    private final JdbcTemplate jdbc;

    public record Tracker(double listenedSec, LocalDateTime lastPingAt, LocalDateTime lastCountedAt) {}

    public int viewCount(Long videoId) {
        Integer views = jdbc.queryForObject("SELECT COALESCE(MAX(view_count), 0) FROM video WHERE id = ?", Integer.class, videoId);
        return views != null ? views : 0;
    }

    /** Loads the viewer's tracker under a row lock (creating it on the first ping) so parallel pings serialise. */
    public Tracker lockTracker(Long userId, Long videoId) {
        jdbc.update("INSERT IGNORE INTO video_view_tracker (user_id, video_id) VALUES (?, ?)", userId, videoId);
        return jdbc.queryForObject("SELECT listened_sec, last_ping_at, last_counted_at FROM video_view_tracker "
                        + "WHERE user_id = ? AND video_id = ? FOR UPDATE",
                (rs, i) -> new Tracker(rs.getDouble(1), toLocal(rs.getTimestamp(2)), toLocal(rs.getTimestamp(3))),
                userId, videoId);
    }

    public void saveTracker(Long userId, Long videoId, double listenedSec, LocalDateTime lastPingAt, LocalDateTime lastCountedAt) {
        jdbc.update("UPDATE video_view_tracker SET listened_sec = ?, last_ping_at = ?, last_counted_at = ? "
                        + "WHERE user_id = ? AND video_id = ?",
                listenedSec, toTimestamp(lastPingAt), toTimestamp(lastCountedAt), userId, videoId);
    }

    /** Adds one view and returns the new total; the row lock keeps each returned total unique. */
    public int incrementViewCount(Long videoId) {
        jdbc.update("UPDATE video SET view_count = view_count + 1 WHERE id = ?", videoId);
        return viewCount(videoId);
    }

    public Set<Integer> rewardedMilestones(Long videoId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT milestone FROM shadowing_view_milestone WHERE video_id = ?", Integer.class, videoId));
    }

    /** Returns false when the milestone was already recorded, so each one is rewarded once. */
    public boolean recordMilestone(Long videoId, int milestone, Long ownerId, int credit) {
        return jdbc.update("INSERT IGNORE INTO shadowing_view_milestone (video_id, milestone, owner_id, credit) "
                + "VALUES (?, ?, ?, ?)", videoId, milestone, ownerId, credit) == 1;
    }

    private static LocalDateTime toLocal(Timestamp value) { return value != null ? value.toLocalDateTime() : null; }

    private static Timestamp toTimestamp(LocalDateTime value) { return value != null ? Timestamp.valueOf(value) : null; }
}
