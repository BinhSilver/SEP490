package com.jlas.assessment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record JlptHistoryEntryResponse(
        long id,
        String examCode,
        String examTitle,
        String level,
        String mode,
        String partCode,
        int correctAnswers,
        int answeredQuestions,
        int totalQuestions,
        BigDecimal scorePercent,
        Integer totalScore,
        Map<String, Integer> sectionScores,
        boolean passed,
        LocalDateTime submittedAt,
        boolean fullAttempt,
        boolean reviewAvailable) {
}
