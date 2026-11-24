package com.jlas.jlptpath.service.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Owner 24/11 "Lộ trình của tôi": paths are peers, so the JLPT path becoming ACTIVE (created or resumed)
 * no longer pauses communication paths. It only checks the limit of two paths studied at once: when two communication
 * paths already run, MY_PATHS_FULL lets the page ask which one to pause (the "Lộ trình của tôi" API pauses the chosen
 * one first). Runs inside the caller's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathCommPathPauser {

    private final JdbcTemplate db;

    public JpathCommPathPauser(JdbcTemplate db) {
        this.db = db;
    }

    /** Throws MY_PATHS_FULL when the learner already studies two communication paths; returns how many run. */
    public int pauseCommPaths(Long userId) {
        int active;
        try {
            Integer n = db.queryForObject("SELECT COUNT(*) FROM cpath_user_path WHERE user_id = ? AND status = 'ACTIVE'", Integer.class, userId);
            active = n == null ? 0 : n;
        } catch (DataAccessException e) {
            // a database without the communication tables (feature not installed) has nothing running
            if (e instanceof org.springframework.jdbc.BadSqlGrammarException) return 0;
            throw e;
        }
        if (active >= com.jlas.cpath.CpathService.MAX_ACTIVE) {
            throw new com.jlas.common.exception.ApiException(com.jlas.common.exception.ErrorCode.MY_PATHS_FULL);
        }
        return active;
    }
}
