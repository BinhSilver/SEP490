package com.jlas.learning.dto;

import java.util.List;

/** Payload returned when a learner opens a lesson quiz and a new attempt is created. */
public record QuizStartResponse(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long lessonId,
        List<QuizQuestionResponse> questions) {
}
