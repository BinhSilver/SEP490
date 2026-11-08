package com.jlas.learning.dto;

import java.util.List;

/**
 * Payload returned when a learner opens a quiz: a new attempt, or the open attempt being resumed.
 *
 * @param lessonId null for a unit's final test
 * @param answered answers already recorded on a resumed attempt (empty for a new one)
 * @param timeLimitSeconds whole time allowed for a timed test (a unit final); null when untimed
 * @param secondsLeft time left on the server clock when the attempt was opened; null when untimed
 * @param leaveCount times the learner already left this timed attempt (tab, window, fullscreen, reload)
 * @param leaveLimit leaving this many times closes the attempt; null when untimed
 */
public record QuizStartResponse(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long lessonId,
        List<QuizQuestionResponse> questions,
        List<QuizAnswerFeedbackResponse> answered,
        Integer timeLimitSeconds,
        Long secondsLeft,
        Integer leaveCount,
        Integer leaveLimit) {
}
