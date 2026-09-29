package com.jlas.learning.dto;

/** [JLAS-W0193] Task 10 — <b>baseline</b> Reading/Listening evidence for the path summary, read exclusively
 * from the Smart Test snapshot taken before the path started. {@code note} always discloses this plainly
 * (design spec §6.1: "Nếu Reading/Listening không được đánh giá lại trong Learning Path, UI phải ghi rõ
 * 'chưa được đánh giá lại trong lộ trình'") so the UI never implies this is fresh, re-verified evidence.
 * {@code hasEvidence} is false when the assessment attempt has no valid sample for this skill at all — this
 * is reported as "insufficient evidence", never coerced into a 0% score.
 *
 * <p>[JLAS-W0193] RC Task 6 — since RC Tasks 1-5 shipped playable, persisted Reading/Listening activities,
 * this record now co-exists with {@link InteractivePathSummaryResponse#comprehensionInPath()}, which reports
 * the learner's actual in-path micro-check evidence for the same two skills. This record is deliberately
 * left unchanged and continues to mean exactly "baseline, from Smart Test, not re-verified here" — RC Task 6
 * never repurposes or overwrites it with in-path data. */
public record InteractiveComprehensionEvidence(
        String skill,
        boolean hasEvidence,
        Integer assessedCount,
        Integer wrongCount,
        Integer correctPercent,
        boolean weak,
        String note
) {
}
