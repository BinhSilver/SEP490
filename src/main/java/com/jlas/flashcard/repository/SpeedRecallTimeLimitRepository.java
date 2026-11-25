package com.jlas.flashcard.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The time a learner chose per word for a speed-recall session (flashcard_speed_recall_session.time_limit_seconds,
 * migration 074). JDBC rather than an entity field, so schema validation does not stop a database that has not run
 * the migration yet; {@link com.jlas.flashcard.config.SpeedRecallTimeLimitSchemaInitializer} adds the column at startup.
 * Without the column every session keeps the automatic time.
 */
@Slf4j
@Repository
public class SpeedRecallTimeLimitRepository {
    private final JdbcTemplate jdbc;

    public SpeedRecallTimeLimitRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Seconds per word chosen for the session; empty for the automatic time. */
    public Optional<Integer> find(Long sessionId) {
        try {
            List<Integer> rows = jdbc.queryForList(
                    "SELECT time_limit_seconds FROM flashcard_speed_recall_session WHERE session_id = ?", Integer.class, sessionId);
            return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
        } catch (DataAccessException missingColumn) {
            return Optional.empty();
        }
    }

    public void save(Long sessionId, Integer seconds) {
        if (seconds == null) return;
        try {
            jdbc.update("UPDATE flashcard_speed_recall_session SET time_limit_seconds = ? WHERE session_id = ?", seconds, sessionId);
        } catch (DataAccessException missingColumn) {
            log.warn("Speed recall time per word not saved for session {}: {}", sessionId, missingColumn.getMessage());
        }
    }
}
