package com.jlas.assessment.dto;

public record JlptAdminExamDetailResponse(
        JlptAdminExamSummaryResponse exam,
        JlptExamResponse content) {
}
