package com.jlas.learning.dto;

import java.time.LocalDateTime;
import java.util.List;

/** [JLAS-W0193] Task 10 — the real content behind {@code GET /learning/path/v2/summary} (Task 8 shipped only
 * a placeholder route for this). {@code certificateEligible} mirrors exactly the gate the certificate page
 * itself enforces (every {@code GOAL_LEARNING} package in the path {@code COMPLETED}; warm-up packages are
 * prerequisites, not counted requirements — design spec §6.3), so the summary page can offer or withhold the
 * certificate CTA without duplicating that business rule. */
public record InteractivePathSummaryResponse(
        Long pathId,
        String status,
        LocalDateTime completedAt,
        List<InteractiveSkillProgress> skills,
        InteractiveDialogueSummary dialogue,
        List<InteractiveComprehensionEvidence> comprehension,
        InteractiveReadinessConfidence readiness,
        boolean certificateEligible
) {
    public InteractivePathSummaryResponse {
        skills = List.copyOf(skills);
        comprehension = List.copyOf(comprehension);
    }
}
