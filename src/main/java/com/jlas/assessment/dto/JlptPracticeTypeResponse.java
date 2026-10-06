package com.jlas.assessment.dto;

public record JlptPracticeTypeResponse(
        String code,
        String section,
        String nameVi,
        String nameJa,
        int questionCount) {
}
