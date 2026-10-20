package com.jlas.learning.dto;

/**
 * Body of {@code POST /api/learning/paths}. References the finalized assessment
 * attempt only; the server never trusts a userId or assessment evidence from the client.
 */
public record CreateLearningPathRequest(Long assessmentAttemptId, String reviewStartLevel, String discrepancyChoice) {
    public CreateLearningPathRequest(Long assessmentAttemptId) {
        this(assessmentAttemptId, null, null);
    }

    public CreateLearningPathRequest(Long assessmentAttemptId, String reviewStartLevel) {
        this(assessmentAttemptId, reviewStartLevel, null);
    }
}
