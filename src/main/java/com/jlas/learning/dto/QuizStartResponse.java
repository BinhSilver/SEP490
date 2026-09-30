package com.jlas.learning.dto;

import java.util.List;

/**
 * Payload returned when a learner opens a quiz: a new attempt, or the open attempt being resumed.
 *
 * @param lessonId null for a unit's final test
 * @param answered answers already recorded on a resumed attempt (empty for a new one)
 */
public record QuizStartResponse(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long lessonId,
        List<QuizQuestionResponse> questions,
        List<QuizAnswerFeedbackResponse> answered) {
}
