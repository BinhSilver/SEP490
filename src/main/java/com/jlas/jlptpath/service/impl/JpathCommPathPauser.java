package com.jlas.jlptpath.service.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] I3: one main goal (owner 17/11) — when the JLPT path becomes ACTIVE (created or resumed), the
 * communication paths step aside (PAUSED, progress kept) in the SAME transaction, so the two paths never end up
 * both ACTIVE or both PAUSED because the browser's separate "pause-for-jlpt" request failed or never ran. The
 * mirror of {@code CpathService.createPath} pausing an ACTIVE JLPT path.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathCommPathPauser {

    private final JdbcTemplate db;

    public JpathCommPathPauser(JdbcTemplate db) {
        this.db = db;
    }

    /** Pauses every ACTIVE communication path of the learner; returns how many were paused. */
    public int pauseCommPaths(Long userId) {
        // owner 24/11: /learning routes by the survey goal, so the main goal follows the path that just became ACTIVE;
        // otherwise a resumed JLPT path stays hidden behind the communication path
        try {
            db.update("UPDATE user_learning_survey SET goal = 'JLPT' WHERE user_id = ?", userId);
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // no survey table (tests / feature not installed): nothing to follow
        }
        try {
            return db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND status = 'ACTIVE'", userId);
        } catch (DataAccessException e) {
            // a database without the communication tables (feature not installed) has nothing to pause
            if (e instanceof org.springframework.jdbc.BadSqlGrammarException) return 0;
            throw e;
        }
    }
}
