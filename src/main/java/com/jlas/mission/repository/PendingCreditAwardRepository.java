package com.jlas.mission.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Outbox of credit awards produced by learning events. An award is written in the same transaction
 * as the progress that earned it and applied to the ledger afterwards, so a failing credit service
 * delays the reward instead of losing it.
 */
@Repository
@RequiredArgsConstructor
public class PendingCreditAwardRepository {

    public record PendingAward(Long id, Long userId, int amount, String reason, String referenceType,
                               Long referenceId, String note, String celebrationKind, String celebrationTitle,
                               Integer streak, int attempts, boolean completed) {
    }

    private static final RowMapper<PendingAward> MAPPER = (rs, row) -> new PendingAward(
            rs.getLong("id"), rs.getLong("user_id"), rs.getInt("amount"), rs.getString("reason"),
            rs.getString("reference_type"), rs.getLong("reference_id"), rs.getString("note"),
            rs.getString("celebration_kind"), rs.getString("celebration_title"),
            (Integer) rs.getObject("streak", Integer.class), rs.getInt("attempts"),
            rs.getTimestamp("completed_at") != null);

    private final JdbcTemplate jdbc;

    /** Queues an award; a second enqueue of the same reference is ignored. */
    public void enqueue(Long userId, int amount, String reason, String referenceType, Long referenceId,
                        String note, String celebrationKind, String celebrationTitle, Integer streak) {
        jdbc.update("INSERT IGNORE INTO pending_credit_award (user_id, amount, reason, reference_type, reference_id, "
                        + "note, celebration_kind, celebration_title, streak) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, amount, reason, referenceType, referenceId, note, celebrationKind, celebrationTitle, streak);
    }

    public List<Long> findDueIds(int limit) {
        return jdbc.queryForList("SELECT id FROM pending_credit_award WHERE completed_at IS NULL "
                + "AND next_attempt_at <= CURRENT_TIMESTAMP(6) ORDER BY id LIMIT ?", Long.class, limit);
    }

    public List<Long> findOpenIdsForUser(Long userId) {
        return jdbc.queryForList("SELECT id FROM pending_credit_award WHERE user_id = ? AND completed_at IS NULL "
                + "ORDER BY id", Long.class, userId);
    }

    public Optional<PendingAward> lock(Long id) {
        return jdbc.query("SELECT * FROM pending_credit_award WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public void markCompleted(Long id) {
        jdbc.update("UPDATE pending_credit_award SET completed_at = CURRENT_TIMESTAMP(6), last_error = NULL "
                + "WHERE id = ?", id);
    }

    /** Backs off exponentially, capped at one hour, so a broken dependency is not hammered. */
    public void markFailed(Long id, String error) {
        jdbc.update("UPDATE pending_credit_award SET attempts = attempts + 1, last_error = ?, "
                        + "next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL LEAST(3600, 30 * POW(2, attempts)) SECOND) "
                        + "WHERE id = ? AND completed_at IS NULL",
                error == null ? null : error.substring(0, Math.min(500, error.length())), id);
    }

    public int countOpen(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pending_credit_award "
                + "WHERE user_id = ? AND completed_at IS NULL", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
