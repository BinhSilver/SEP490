package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import java.time.LocalDateTime;
import java.util.List;

/** [JLAS-W0193] Micro-check submit result: score + per-question feedback plus server-derived
 * attempt/cooldown state. {@code attemptsRemaining}/{@code cooldownUntil}/{@code reviewRequired}
 * are authoritative — the client never computes retry eligibility. */
public record MicroCheckResult(
        int totalQuestions,
        int correctAnswers,
        int scorePercent,
        boolean passed,
        int passThresholdPercent,
        int attemptsUsed,
        int attemptsRemaining,
        LocalDateTime cooldownUntil,
        boolean reviewRequired,
        LearningActivityState activityState,
        List<LessonQuizResult.AnswerReview> review
) {
    public MicroCheckResult {
        review = List.copyOf(review);
    }
}
