package com.jlas.learning.dto;

import java.math.BigDecimal;
import java.util.List;

/** Full result of a submitted quiz attempt, including per-answer detail. */
public record QuizResultResponse(
        Long attemptId,
        Long quizId,
        String quizTitle,
        BigDecimal scorePercent,
        int correctCount,
        int totalCount,
        List<QuizAnswerDetailResponse> answers) {
}
