package com.jlas.learning.dto;

/**
 * Immediate feedback for one answer given while the quiz is still open. The answer is recorded when it
 * is first checked, so it cannot be changed afterwards and counts as-is when the attempt is submitted.
 *
 * @param correctOption 1-based index of the correct choice within the shown options (MULTIPLE_CHOICE)
 */
public record QuizAnswerFeedbackResponse(
        Long questionId,
        boolean correct,
        Integer selectedOption,
        String answerText,
        Integer correctOption,
        String correctAnswerText,
        String explanation) {
}
