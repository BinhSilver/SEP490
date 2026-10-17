package com.jlas.learning.service;

/** [JLAS-W0108] Task 9 — the only way the Learning module reads a JLPT Mock Test outcome. Learning never
 * creates, scores or grades an exam; it asks the Assessment-owned result whether an attempt qualifies. */
public interface JlptQualificationPort {
    record Result(boolean qualified, String reasonCode) {
    }

    /** Verifies that {@code attemptId} belongs to {@code userId}, is a SUBMITTED JLPT attempt of exactly
     * {@code targetLevel}, and that the total score and every section score pass. */
    Result verify(Long userId, Long attemptId, String targetLevel);
}
