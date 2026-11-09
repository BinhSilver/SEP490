package com.jlas.shadowing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** The numbers on a video card, read for a whole list in one statement so the cost does not grow with its length. */
@Repository
public class VideoSummaryStatsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public VideoSummaryStatsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Stats(long subtitles, int views, Double averageRating, long ratings, long comments,
                        String selectedLevel, String suggestedLevel) {
        public static final Stats NONE = new Stats(0, 0, null, 0, 0, null, null);
    }

    public Map<Long, Stats> forVideos(Collection<Long> videoIds) {
        Map<Long, Stats> stats = new HashMap<>();
        if (videoIds.isEmpty()) return stats;
        jdbc.query("""
                SELECT v.id, COALESCE(v.view_count, 0) view_total,
                       (SELECT COUNT(*) FROM subtitle s WHERE s.video_id = v.id) subtitle_total,
                       (SELECT AVG(u.rating) FROM video_user_state u WHERE u.video_id = v.id) average_rating,
                       (SELECT COUNT(u.rating) FROM video_user_state u WHERE u.video_id = v.id) rating_total,
                       (SELECT COUNT(*) FROM content_comment c
                         WHERE c.target_type = 'VIDEO' AND c.target_id = v.id AND c.is_hidden = FALSE) comment_total,
                       p.selected_jlpt_level, p.suggested_jlpt_level
                FROM video v
                LEFT JOIN shadowing_preparation_job p ON p.video_id = v.id
                WHERE v.id IN (:ids)
                """, new MapSqlParameterSource("ids", videoIds), rs -> {
            Number average = (Number) rs.getObject("average_rating");
            stats.put(rs.getLong("id"), new Stats(rs.getLong("subtitle_total"), rs.getInt("view_total"),
                    average == null ? null : average.doubleValue(), rs.getLong("rating_total"), rs.getLong("comment_total"),
                    rs.getString("selected_jlpt_level"), rs.getString("suggested_jlpt_level")));
        });
        return stats;
    }
}
