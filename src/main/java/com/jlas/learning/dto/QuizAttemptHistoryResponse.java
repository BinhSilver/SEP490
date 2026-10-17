package com.jlas.learning.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/** A saved, owner-scoped quiz replay for the learner's quiz-results history bar. */
public record QuizAttemptHistoryResponse(Long attemptId, int attemptNumber, int batchIndex, String mode,
        int scorePercent, int correctAnswers, int totalQuestions, boolean passed, LocalDateTime submittedAt,
        List<AttemptFeedbackResponse.Item> review,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) ComprehensionExerciseSource source) {
    public QuizAttemptHistoryResponse {
        review = List.copyOf(review);
    }

    /** Keeps the existing history contract for skills without a passage/audio source. */
    public QuizAttemptHistoryResponse(Long attemptId, int attemptNumber, int batchIndex, String mode,
            int scorePercent, int correctAnswers, int totalQuestions, boolean passed, LocalDateTime submittedAt,
            List<AttemptFeedbackResponse.Item> review) {
        this(attemptId, attemptNumber, batchIndex, mode, scorePercent, correctAnswers, totalQuestions,
                passed, submittedAt, review, null, null);
    }
}
