package com.jlas.learning.dto;

/** Explanation produced on demand from the immutable server-side attempt snapshot. */
public record QuizExplanationResponse(Long attemptId, Long questionId, String explanation, String source,
        String correctAnswerTranslationVi, String exampleJa, String exampleVi, String scriptTranslationVi) { }
