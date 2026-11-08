package com.jlas.learning.dto;

/**
 * Outcome of a create call: {@code created} distinguishes a fresh path (201)
 * from an idempotent reuse of the same generation identity (200).
 */
public record CreateLearningPathResult(LearningPathResponse path, boolean created) { }
