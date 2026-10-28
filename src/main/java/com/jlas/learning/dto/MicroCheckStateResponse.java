package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import java.time.LocalDateTime;

/** [JLAS-W0193] Read-only micro-check state: current batch, attempt summary and retry eligibility. */
public record MicroCheckStateResponse(
        Long activityId,
        LearningActivityState activityState,
        int batchCount,
        int currentBatch,
        int attemptsUsed,
        int attemptsRemaining,
        LocalDateTime cooldownUntil,
        boolean reviewRequired,
        boolean checkEligible,
        boolean passed,
        Integer firstScore,
        Integer latestScore,
        Integer bestScore,
        Long inProgressAttemptId
) {
    public MicroCheckStateResponse(
            Long activityId,
            LearningActivityState activityState,
            int batchCount,
            int currentBatch,
            int attemptsUsed,
            int attemptsRemaining,
            LocalDateTime cooldownUntil,
            boolean reviewRequired,
            boolean checkEligible,
            boolean passed,
            Integer firstScore,
            Integer latestScore,
            Integer bestScore
    ) {
        this(activityId, activityState, batchCount, currentBatch, attemptsUsed, attemptsRemaining,
                cooldownUntil, reviewRequired, checkEligible, passed, firstScore, latestScore, bestScore, null);
    }
}
