package com.jlas.kaiwa.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Ephemeral state for an active call. Persist only the final summary in SQL;
 * transcript and engine state live here while the session is active.
 */
public record ConversationRuntimeState(
        Long sessionId,
        Long scenarioId,
        String currentStateCode,
        Set<String> completedGoalCodes,
        List<ConversationRuntimeMessage> messages,
        Instant updatedAt) {}
