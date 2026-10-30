package com.jlas.learning.dto;

import java.time.LocalDateTime;

/** [JLAS-W0108] Task 9 — {@code newlyCompleted} is true only for the call that performed the transition. */
public record PathCompletionResponse(Long pathId, String status, boolean newlyCompleted,
        String targetLevel, LocalDateTime completedAt) {
}
