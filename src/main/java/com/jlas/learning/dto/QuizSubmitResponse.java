package com.jlas.learning.dto;

import java.math.BigDecimal;

/** Grading summary returned immediately after a quiz is submitted; {@code passed} means the pass mark was reached. */
public record QuizSubmitResponse(
        Long attemptId,
        BigDecimal scorePercent,
        int correctCount,
        int totalCount,
        boolean passed) {
}
