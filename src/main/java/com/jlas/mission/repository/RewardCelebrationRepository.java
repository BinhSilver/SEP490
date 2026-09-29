package com.jlas.mission.repository;

import com.jlas.mission.dto.CelebrationResponse;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Rewards the learner has not seen animated yet. Rewards granted in the background (daily goal,
 * streak milestones) land here so the next page load can celebrate them exactly once.
 */
@Repository
@RequiredArgsConstructor
public class RewardCelebrationRepository {

    private final JdbcTemplate jdbc;

    /** {@code eventKey} makes the insert idempotent: one celebration per business fact. */
    public void add(Long userId, String eventKey, String kind, String title, int amount, Integer streak) {
        jdbc.update("INSERT IGNORE INTO reward_celebration (user_id, event_key, kind, title, amount, streak) "
                + "VALUES (?, ?, ?, ?, ?, ?)", userId, eventKey, kind, title, amount, streak);
    }

    public List<CelebrationResponse> findUnseen(Long userId, int limit) {
        return jdbc.query("SELECT id, kind, title, amount, streak, created_at FROM reward_celebration "
                        + "WHERE user_id = ? AND seen_at IS NULL ORDER BY id LIMIT ?",
                (rs, row) -> new CelebrationResponse(rs.getLong("id"), rs.getString("kind"), rs.getString("title"),
                        rs.getInt("amount"), (Integer) rs.getObject("streak", Integer.class),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                userId, limit);
    }

    public int markSeen(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Object[] args = new Object[ids.size() + 1];
        args[0] = userId;
        int index = 1;
        for (Long id : ids) {
            args[index++] = id;
        }
        return jdbc.update("UPDATE reward_celebration SET seen_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_id = ? AND seen_at IS NULL AND id IN (" + placeholders + ")", args);
    }
}
