package com.jlas.learning.dto;

import java.time.LocalDateTime;
import java.util.List;

/** [JLAS-W0108] REVIEW feedback: the latest submitted attempt of one batch, exactly as stored (read-only). */
public record AttemptFeedbackResponse(int batchIndex, int scorePercent, int correctAnswers, int totalQuestions,
        boolean passed, LocalDateTime submittedAt, List<Item> review) {
    public AttemptFeedbackResponse {
        review = List.copyOf(review);
    }

    public record Item(Long questionId, String questionText, Integer correctOption, Integer selectedOption,
            boolean correct, String explanation) { }
}
