package com.jlas.mission.repository;

import com.jlas.common.event.LearningEvent;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access for mission bookkeeping: event receipts, row bootstrap and learner timezone.
 *
 * <p>Rows are bootstrapped before the caller locks them with {@code SELECT ... FOR UPDATE}. An existing
 * row is detected with a plain (non-locking) read and left alone: running {@code INSERT IGNORE} or an
 * upsert against it would take a lock that two concurrent requests then both try to upgrade, which
 * InnoDB resolves with a deadlock. Only a missing row is inserted.
 */
@Repository
@RequiredArgsConstructor
public class MissionJdbcRepository {

    private final JdbcTemplate jdbc;

    /** Returns true the first time an event id is seen, false for a replay. */
    public boolean recordReceipt(LearningEvent event) {
        return jdbc.update("INSERT IGNORE INTO learning_event_receipt (event_id, user_id, event_type, occurred_at) "
                        + "VALUES (?, ?, ?, ?)",
                event.eventId(), event.userId(), event.type().name(),
                Timestamp.valueOf(LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))) == 1;
    }

    public void ensureDailyProgress(Long userId, LocalDate day) {
        if (!exists("SELECT COUNT(*) FROM user_daily_progress WHERE user_id = ? AND progress_date = ?", userId, day)) {
            jdbc.update("INSERT IGNORE INTO user_daily_progress (user_id, progress_date) VALUES (?, ?)", userId, day);
        }
    }

    public void ensureUserMission(Long userId, Long definitionId, String occurrenceKey) {
        if (!exists("SELECT COUNT(*) FROM user_mission WHERE user_id = ? AND mission_definition_id = ? "
                + "AND occurrence_key = ?", userId, definitionId, occurrenceKey)) {
            jdbc.update("INSERT IGNORE INTO user_mission (user_id, mission_definition_id, occurrence_key) VALUES (?, ?, ?)",
                    userId, definitionId, occurrenceKey);
        }
    }

    public void ensureStreak(Long userId) {
        if (!exists("SELECT COUNT(*) FROM user_streaks WHERE user_id = ?", userId)) {
            jdbc.update("INSERT IGNORE INTO user_streaks (user_id) VALUES (?)", userId);
        }
    }

    private boolean exists(String countSql, Object... args) {
        Integer count = jdbc.queryForObject(countSql, Integer.class, args);
        return count != null && count > 0;
    }

    public void saveTargets(Long userId, int vocabulary, int quizzes, int minutes) {
        jdbc.update("INSERT INTO user_learning_targets (user_id, daily_vocab_target, daily_quiz_target, daily_study_minutes) "
                        + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE daily_vocab_target = VALUES(daily_vocab_target), "
                        + "daily_quiz_target = VALUES(daily_quiz_target), daily_study_minutes = VALUES(daily_study_minutes)",
                userId, vocabulary, quizzes, minutes);
    }

    public Optional<String> findZone(Long userId) {
        List<String> zones = jdbc.queryForList("SELECT zone_id FROM user_time_zone WHERE user_id = ?", String.class, userId);
        return zones.stream().findFirst();
    }

    public void saveZone(Long userId, String zoneId) {
        jdbc.update("INSERT INTO user_time_zone (user_id, zone_id) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE zone_id = VALUES(zone_id)", userId, zoneId);
    }

    /** Streak rows that may have missed a day; the caller re-checks each one in the learner's zone. */
    public List<Long> findStreakUsersLastCompletedBefore(LocalDate day, int limit) {
        return jdbc.queryForList("SELECT user_id FROM user_streaks WHERE current_streak > 0 "
                        + "AND last_completed_date < ? ORDER BY last_completed_date, user_id LIMIT ?",
                Long.class, day, limit);
    }
}
