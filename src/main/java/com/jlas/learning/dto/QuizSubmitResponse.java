package com.jlas.learning.dto;

import java.math.BigDecimal;

/** Grading summary returned immediately after a quiz is submitted. */
public record QuizSubmitResponse(
        Long attemptId,
        BigDecimal scorePercent,
        int correctCount,
        int totalCount) {
}
