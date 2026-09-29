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
        Integer bestScore
) { }
