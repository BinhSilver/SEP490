package com.jlas.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

/** UAT 2026-10-06: a database constraint error reached the learner as raw SQL ("could not execute statement
 * [Duplicate entry ...]"). Database failures must answer with the shared envelope and a neutral Vietnamese message;
 * the SQL stays in the server log only. */
class GlobalApiExceptionHandlerDataAccessTest {
    private final GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();

    @Test
    void constraintViolationIsAConflictWithoutSql() {
        var response = handler.dataAccess(new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry '78-VOCABULARY-1' for key 'uk']",
                new SQLIntegrityConstraintViolationException("Duplicate entry")));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONFLICT", response.getBody().getCode());
        assertFalse(response.getBody().getMessage().contains("Duplicate"));
        assertFalse(response.getBody().getMessage().contains("insert into"));
    }

    @Test
    void otherDatabaseFailuresAreInternalErrorsWithoutSql() {
        var response = handler.dataAccess(new QueryTimeoutException("select * from users where secret"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", response.getBody().getCode());
        assertFalse(response.getBody().getMessage().contains("select"));
    }
}
