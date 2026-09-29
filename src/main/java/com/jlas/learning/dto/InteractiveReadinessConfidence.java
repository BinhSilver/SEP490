package com.jlas.learning.dto;

/** [JLAS-W0193] Task 10 — readiness is an internal estimate, never a JLPT pass confirmation (design spec
 * §6.2). {@code level} is one of {@code LOW}/{@code MEDIUM} — deliberately never {@code HIGH}: Reading/
 * Listening evidence is always sourced from a (possibly stale) Smart Test snapshot rather than being
 * re-verified inside the Learning Path itself (see {@link InteractiveComprehensionEvidence}), so this
 * response never claims full confidence while a third of the five JLPT skills rests on that gap.
 * {@code explanation} always names the concrete evidence/gap behind the level — the level is never shown
 * as a bare number the learner has to trust blindly. */
public record InteractiveReadinessConfidence(
        String level,
        String explanation
) {
}
