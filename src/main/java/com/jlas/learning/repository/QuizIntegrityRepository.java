package com.jlas.learning.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * Integrity events of a timed quiz attempt (migration 057). JDBC because the table is created at
 * startup and stays out of the JPA model that schema validation checks.
 */
@Repository
public class QuizIntegrityRepository {
    /** Every event the page may report; RESUME is written by the server when an open attempt is reopened. */
    public static final Set<String> TYPES = Set.of("TAB_HIDDEN", "WINDOW_BLUR", "FULLSCREEN_EXIT", "MULTI_TAB",
            "RESUME", "COPY", "PASTE", "CONTEXT_MENU", "DEVTOOLS", "TIME_UP", "AUTO_SUBMIT");
    /** Events that mean the learner left the test; they count toward the limit that closes the attempt. */
    public static final Set<String> LEAVING = Set.of("TAB_HIDDEN", "WINDOW_BLUR", "FULLSCREEN_EXIT", "MULTI_TAB", "RESUME");

    private final JdbcTemplate jdbc;

    public QuizIntegrityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(Long attemptId, String type, String detail) {
        jdbc.update("INSERT INTO assessment_attempt_integrity_event (attempt_id, event_type, detail) VALUES (?, ?, ?)",
                attemptId, type, detail);
    }

    public int count(Long attemptId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt_integrity_event WHERE attempt_id = ?",
                Integer.class, attemptId);
        return n == null ? 0 : n;
    }

    /** How many times the learner left the attempt (tab, window, fullscreen, second tab, reload). */
    public int leavingCount(Long attemptId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt_integrity_event WHERE attempt_id = ? "
                + "AND event_type IN ('TAB_HIDDEN','WINDOW_BLUR','FULLSCREEN_EXIT','MULTI_TAB','RESUME')",
                Integer.class, attemptId);
        return n == null ? 0 : n;
    }
}
