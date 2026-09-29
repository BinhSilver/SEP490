package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;

/** [JLAS-W0193] Task 7 — result of a heartbeat or reached-bottom submission. Every field is server-computed;
 * there is no client-settable {@code completed}/{@code accumulatedSeconds} anywhere in the request DTOs. */
public record DialogueProgressResult(
        Long activityId,
        LearningActivityState activityState,
        int requiredSeconds,
        int accumulatedSeconds,
        int remainingSeconds,
        boolean reachedBottom,
        boolean completed
) { }
