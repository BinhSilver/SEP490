package com.jlas.learning.dto;

import java.time.LocalDateTime;
import java.util.List;

/** [JLAS-W0193] Task 10 — the real content behind {@code GET /learning/path/v2/summary} (Task 8 shipped only
 * a placeholder route for this). {@code certificateEligible} mirrors exactly the gate the certificate page
 * itself enforces (every {@code GOAL_LEARNING} package in the path {@code COMPLETED}; warm-up packages are
 * prerequisites, not counted requirements — design spec §6.3), so the summary page can offer or withhold the
 * certificate CTA without duplicating that business rule.
 *
 * <p>[JLAS-W0193] RC Task 6 — {@code comprehension} stays exactly what Task 10 shipped: Reading/Listening
 * evidence read only from the Smart Test snapshot, kept forever as the <b>baseline</b> record of what the
 * assessment measured before the path started. {@code comprehensionInPath} is new: first/latest/best +
 * unresolved-batch evidence from the learner's own Reading/Listening micro-check activities inside this path
 * (RC Task 1-5), aggregated the exact same way {@code skills} already aggregates Vocabulary/Kanji/Grammar.
 * The two lists are never merged, averaged or one silently replaced by the other — a learner can see both
 * "what Smart Test measured before" and "what I've actually done in this path," distinctly (RC plan Task 6,
 * rule 3). An unresolved entry in {@code comprehensionInPath} is a required readiness gap exactly like an
 * unresolved entry in {@code skills} (rule 4) — a strong Vocabulary/Kanji/Grammar or baseline score can never
 * hide it.
 *
 * <p>[JLAS-W0193] RC Task 7 — {@code mockTest} is the server-resolved Mock Test hand-off destination
 * ({@code MockTestDestinationProvider.resolveDestination}), never composed or decided by the browser. No
 * real Mock Test owner contract exists in this codebase (re-audited fresh at RC Task 7 time), so this is
 * {@code BLOCKED_INTEGRATION} by default — an honest state the summary page must render as a disabled
 * button, never a fabricated link. */
public record InteractivePathSummaryResponse(
        Long pathId,
        String status,
        LocalDateTime completedAt,
        List<InteractiveSkillProgress> skills,
        InteractiveDialogueSummary dialogue,
        List<InteractiveComprehensionEvidence> comprehension,
        List<InteractiveSkillProgress> comprehensionInPath,
        InteractiveReadinessConfidence readiness,
        boolean certificateEligible,
        MockTestDestination mockTest
) {
    public InteractivePathSummaryResponse {
        skills = List.copyOf(skills);
        comprehension = List.copyOf(comprehension);
        comprehensionInPath = List.copyOf(comprehensionInPath);
    }
}
