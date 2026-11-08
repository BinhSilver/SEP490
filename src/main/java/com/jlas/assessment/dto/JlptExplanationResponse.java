package com.jlas.assessment.dto;

public record JlptExplanationResponse(
        String explanation,
        int correctChoice,
        boolean correct,
        boolean aiGenerated) {
}
