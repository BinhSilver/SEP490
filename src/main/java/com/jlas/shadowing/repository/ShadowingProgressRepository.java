package com.jlas.shadowing.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only queries behind the learner's shadowing progress page. */
@Repository
@RequiredArgsConstructor
public class ShadowingProgressRepository {
    private final JdbcTemplate jdbc;

    public record PronunciationTotals(int sentences, int attempts, Double averageScore) {}
    public record DailyScore(LocalDate day, double scoreSum, int attempts) {}
    public record ListeningTotals(int completedSessions, int correct, int questions) {}
    public record RecentVideo(long videoId, String title, boolean hasThumbnail, LocalDateTime lastActivityAt) {}
    public record WeakSentence(long videoId, String videoTitle, String text, double bestScore, int attempts) {}

    /** Distinct videos the user watched, practised pronunciation on, or did listening practice with. */
    public int studiedVideos(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ("
                + "SELECT video_id FROM video_user_state WHERE user_id = ? AND last_watched_at IS NOT NULL "
                + "UNION SELECT video_id FROM pronunciation_attempt WHERE user_id = ? "
                + "UNION SELECT video_id FROM shadowing_practice_session WHERE user_id = ?) v",
                Integer.class, userId, userId, userId);
        return count != null ? count : 0;
    }

    /** Scored attempts since {@code since} (null = all time): distinct sentences, attempt count and mean score. */
    public PronunciationTotals pronunciationTotals(long userId, LocalDateTime since) {
        Timestamp from = since != null ? Timestamp.valueOf(since) : null;
        return jdbc.queryForObject("SELECT COUNT(DISTINCT subtitle_id), COUNT(*), AVG(pronunciation_score) "
                        + "FROM pronunciation_attempt WHERE user_id = ? AND pronunciation_score IS NOT NULL "
                        + "AND (? IS NULL OR created_at >= ?)",
                (rs, i) -> {
                    BigDecimal average = rs.getBigDecimal(3);
                    return new PronunciationTotals(rs.getInt(1), rs.getInt(2), average != null ? average.doubleValue() : null);
                },
                userId, from, from);
    }

    public List<DailyScore> dailyScores(long userId, LocalDateTime since) {
        return jdbc.query("SELECT DATE(created_at), SUM(pronunciation_score), COUNT(*) FROM pronunciation_attempt "
                        + "WHERE user_id = ? AND pronunciation_score IS NOT NULL AND created_at >= ? "
                        + "GROUP BY DATE(created_at)",
                (rs, i) -> new DailyScore(rs.getDate(1).toLocalDate(), rs.getDouble(2), rs.getInt(3)),
                userId, Timestamp.valueOf(since));
    }

    public ListeningTotals listeningTotals(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(correct_count), 0), COALESCE(SUM(total_questions), 0) "
                        + "FROM shadowing_practice_session WHERE user_id = ? AND status = 'COMPLETED'",
                (rs, i) -> new ListeningTotals(rs.getInt(1), rs.getInt(2), rs.getInt(3)), userId);
    }

    public List<RecentVideo> recentVideos(long userId, int limit) {
        return jdbc.query("SELECT a.video_id, v.title, v.thumbnail_url IS NOT NULL, MAX(a.at) AS last_at FROM ("
                        + "SELECT video_id, last_watched_at AS at FROM video_user_state "
                        + "WHERE user_id = ? AND last_watched_at IS NOT NULL "
                        + "UNION ALL SELECT video_id, created_at FROM pronunciation_attempt WHERE user_id = ? "
                        + "UNION ALL SELECT video_id, started_at FROM shadowing_practice_session WHERE user_id = ?"
                        + ") a JOIN video v ON v.id = a.video_id GROUP BY a.video_id, v.title, v.thumbnail_url "
                        + "ORDER BY last_at DESC LIMIT ?",
                (rs, i) -> new RecentVideo(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                        rs.getTimestamp(4).toLocalDateTime()),
                userId, userId, userId, limit);
    }

    /** Sentences whose best score since {@code since} is still under {@code threshold}, weakest first. */
    public List<WeakSentence> weakSentences(long userId, LocalDateTime since, double threshold, int limit) {
        return jdbc.query("SELECT p.video_id, v.title, s.text, MAX(p.pronunciation_score) AS best, COUNT(*) "
                        + "FROM pronunciation_attempt p JOIN subtitle s ON s.id = p.subtitle_id "
                        + "JOIN video v ON v.id = p.video_id "
                        + "WHERE p.user_id = ? AND p.pronunciation_score IS NOT NULL AND p.created_at >= ? "
                        + "GROUP BY p.subtitle_id, p.video_id, v.title, s.text HAVING best < ? "
                        + "ORDER BY best ASC LIMIT ?",
                (rs, i) -> new WeakSentence(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getInt(5)),
                userId, Timestamp.valueOf(since), threshold, limit);
    }
}
