package com.jlas.mission.repository;

import com.jlas.mission.enums.FreezeDayStatus;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * One row per missed day the learner was offered a streak freeze for. The primary key
 * (user_id, freeze_date) makes "offer once" and "use once" hold under concurrent requests.
 *
 * <p>Every read is a locking read on purpose. Callers already hold the learner's user_streaks row
 * lock, and under REPEATABLE READ a plain SELECT would return the transaction's older snapshot:
 * a request that waited for that lock could then miss an offer or a used freeze committed while it
 * waited, and wrongly end the streak.
 */
@Repository
@RequiredArgsConstructor
public class StreakFreezeDayRepository {

    public record FreezeDay(LocalDate date, FreezeDayStatus status, int streakAtRisk, String source) {
    }

    private static final RowMapper<FreezeDay> MAPPER = (rs, row) -> new FreezeDay(
            rs.getDate("freeze_date").toLocalDate(), FreezeDayStatus.valueOf(rs.getString("status")),
            rs.getInt("streak_at_risk"), rs.getString("source"));

    private final JdbcTemplate jdbc;

    public Optional<FreezeDay> find(Long userId, LocalDate date) {
        return lock(userId, date);
    }

    public Optional<FreezeDay> lock(Long userId, LocalDate date) {
        return jdbc.query("SELECT * FROM streak_freeze_day WHERE user_id = ? AND freeze_date = ? FOR UPDATE",
                MAPPER, userId, date).stream().findFirst();
    }

    /** Returns true when this call created the offer, so the learner is notified only once. */
    public boolean offer(Long userId, LocalDate date, int streakAtRisk) {
        return jdbc.update("INSERT IGNORE INTO streak_freeze_day (user_id, freeze_date, status, streak_at_risk) "
                + "VALUES (?, ?, 'OFFERED', ?)", userId, date, streakAtRisk) == 1;
    }

    public void decide(Long userId, LocalDate date, FreezeDayStatus status, String source) {
        jdbc.update("UPDATE streak_freeze_day SET status = ?, source = ?, decided_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_id = ? AND freeze_date = ?", status.name(), source, userId, date);
    }

    public void expireOpenOffersBefore(Long userId, LocalDate date) {
        jdbc.update("UPDATE streak_freeze_day SET status = 'EXPIRED', decided_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_id = ? AND status = 'OFFERED' AND freeze_date < ?", userId, date);
    }

    /** Days in [from, to] protected by a used freeze. */
    public Set<LocalDate> findUsedBetween(Long userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return Set.of();
        }
        List<LocalDate> days = jdbc.queryForList("SELECT freeze_date FROM streak_freeze_day WHERE user_id = ? "
                        + "AND status = 'USED' AND freeze_date BETWEEN ? AND ? FOR UPDATE", java.sql.Date.class, userId, from, to)
                .stream().map(java.sql.Date::toLocalDate).toList();
        return new HashSet<>(days);
    }
}
