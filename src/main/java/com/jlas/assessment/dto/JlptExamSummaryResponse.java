package com.jlas.assessment.dto;

public record JlptExamSummaryResponse(
        String code,
        String title,
        String level,
        Integer year,
        Integer month,
        String status,
        int questionCount,
        int durationMinutes) {
}
