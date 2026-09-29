package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0193] Task 10 — path-level first/latest/best for one micro-check skill (Vocabulary/Kanji/Grammar),
 * aggregated across every activity of that type in the whole path (not just one package): {@code firstScore}
 * is the earliest valid attempt's score, {@code latestScore} the most recent, {@code bestScore} the maximum —
 * never re-derived from an average of the other two. {@code resolved} is true only when every attempted batch
 * of this skill is currently passed; {@code unresolved} lists exactly which batches are not, by activity and
 * batch index, so the summary names the weakness instead of hiding it inside a skill-level percentage.
 * {@code hasEvidence} is false when the learner has never actually submitted a valid attempt for this skill
 * anywhere in the path (an unattempted skill is "no evidence yet", never "0%"). */
public record InteractiveSkillProgress(
        String skill,
        boolean hasEvidence,
        Integer firstScore,
        Integer latestScore,
        Integer bestScore,
        boolean resolved,
        List<InteractiveUnresolvedBatch> unresolved
) {
    public InteractiveSkillProgress {
        unresolved = List.copyOf(unresolved);
    }
}
