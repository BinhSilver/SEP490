package com.jlas.learning.dto;

/**
 * One learner answer. {@code selectedOption} is 1..4 for MULTIPLE_CHOICE;
 * {@code answerText} is the typed text for FILL_BLANK.
 */
public record QuizAnswerSubmission(Long questionId, Integer selectedOption, String answerText) {
}
