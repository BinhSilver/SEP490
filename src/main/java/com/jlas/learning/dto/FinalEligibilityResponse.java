package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0108] Task 9 — whether the learner may take the target level's Mock Test early. The server is the
 * only decision maker; {@code reasonCode} is null when eligible. */
public record FinalEligibilityResponse(
        boolean eligible,
        String targetLevel,
        int requiredTargetPercent,
        List<LearningStageProgressResponse> stages,
        String reasonCode) {
    public FinalEligibilityResponse {
        stages = List.copyOf(stages);
    }
}
