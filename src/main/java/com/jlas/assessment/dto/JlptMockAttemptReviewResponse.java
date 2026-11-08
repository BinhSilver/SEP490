package com.jlas.assessment.dto;

import java.util.Map;

/** Owner-scoped review view of a submitted attempt, reconstructed from its saved answers and canonical exam. */
public record JlptMockAttemptReviewResponse(
        JlptMockResultResponse result,
        JlptExamResponse exam,
        Map<Long, Integer> answers) { }
