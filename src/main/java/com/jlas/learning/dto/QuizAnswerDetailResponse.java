package com.jlas.learning.dto;

import java.util.List;

/** A single graded answer with the correct answer and explanation, for result review. */
public record QuizAnswerDetailResponse(
        Long questionId,
        String questionType,
        String questionText,
        List<String> options,
        Integer selectedOption,
        String answerText,
        Integer correctOption,
        String correctAnswerText,
        Boolean isCorrect,
        String explanation) {
}
