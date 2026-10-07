package com.jlas.assessment.dto;

public record JlptAdminExamSummaryResponse(
        long id,
        String code,
        String title,
        String level,
        Integer year,
        Integer month,
        String status,
        int passingScore,
        int questionCount,
        int durationMinutes) {
}
