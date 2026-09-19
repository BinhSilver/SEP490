package com.jlas.learning.dto;

/**
 * Body of {@code POST /api/learning/paths}. References the finalized assessment
 * attempt only; the server never trusts a userId or assessment evidence from the client.
 */
public record CreateLearningPathRequest(Long assessmentAttemptId) { }
