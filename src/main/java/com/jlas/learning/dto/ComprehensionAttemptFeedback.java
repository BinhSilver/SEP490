package com.jlas.learning.dto;

import java.time.LocalDateTime;
import java.util.List;

/** [JLAS-W0108] REVIEW of one Reading/Listening batch. {@code sourceStatus} is AVAILABLE, or SOURCE_SNAPSHOT_UNAVAILABLE for
 * sessions issued before snapshots existed - in which case {@code source} is null and is never rebuilt from current content. */
public record ComprehensionAttemptFeedback(int batchIndex, int scorePercent, int correctAnswers, int totalQuestions,
        boolean passed, LocalDateTime submittedAt, String sourceStatus, ComprehensionExerciseSource source,
        List<AttemptFeedbackResponse.Item> review) {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String UNAVAILABLE = "SOURCE_SNAPSHOT_UNAVAILABLE";

    public ComprehensionAttemptFeedback {
        review = List.copyOf(review);
    }
}
