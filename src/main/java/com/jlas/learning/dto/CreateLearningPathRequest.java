package com.jlas.learning.dto;

/**
 * Body of {@code POST /api/learning/paths}. References the finalized assessment
 * attempt only; the server never trusts a userId or assessment evidence from the client.
 */
public record CreateLearningPathRequest(Long assessmentAttemptId, String reviewStartLevel, String discrepancyChoice,
        Boolean kanaKnown, String entryChoice) {
    public CreateLearningPathRequest(Long assessmentAttemptId) {
        this(assessmentAttemptId, null, null, null, null);
    }

    public CreateLearningPathRequest(Long assessmentAttemptId, String reviewStartLevel) {
        this(assessmentAttemptId, reviewStartLevel, null, null, null);
    }

    public CreateLearningPathRequest(Long assessmentAttemptId, String reviewStartLevel, String discrepancyChoice) {
        this(assessmentAttemptId, reviewStartLevel, discrepancyChoice, null, null);
    }
}
