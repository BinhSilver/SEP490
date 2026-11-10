package com.jlas.flashcard.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The numbers on a flashcard set card, read for a whole page in one statement. It reads committed rows, so use it
 * only where nothing was written earlier in the same transaction.
 */
@Repository
public class FlashcardSetStatsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FlashcardSetStatsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Stats(long cards, long studies, Double averageRating, int ratings, int comments) {
        public static final Stats NONE = new Stats(0, 0, null, 0, 0);
    }

    public Map<Long, Stats> forSets(Collection<Long> setIds) {
        Map<Long, Stats> stats = new HashMap<>();
        if (setIds.isEmpty()) return stats;
        jdbc.query("""
                SELECT s.id,
                       (SELECT COUNT(*) FROM flashcard_card c WHERE c.set_id = s.id) card_total,
                       (SELECT COUNT(*) FROM flashcard_study_session x
                         WHERE x.set_id = s.id AND x.status = 'COMPLETED') study_total,
                       (SELECT SUM(u.rating) FROM flashcard_user_set_state u WHERE u.set_id = s.id) rating_sum,
                       (SELECT COUNT(u.rating) FROM flashcard_user_set_state u WHERE u.set_id = s.id) rating_total,
                       (SELECT COUNT(*) FROM content_comment m
                         WHERE m.target_type = 'FLASHCARD_SET' AND m.target_id = s.id AND m.is_hidden = FALSE) comment_total
                FROM flashcard_set s
                WHERE s.id IN (:ids)
                """, new MapSqlParameterSource("ids", setIds), rs -> {
            int ratings = rs.getInt("rating_total");
            // SUM / COUNT in Java: MySQL's AVG rounds to 4 decimals, which would change the value the API returns.
            Double average = ratings == 0 ? null : rs.getLong("rating_sum") / (double) ratings;
            stats.put(rs.getLong("id"), new Stats(rs.getLong("card_total"), rs.getLong("study_total"), average,
                    ratings, rs.getInt("comment_total")));
        });
        return stats;
    }
}
