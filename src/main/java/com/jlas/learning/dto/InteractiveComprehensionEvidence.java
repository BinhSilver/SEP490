package com.jlas.learning.dto;

/** [JLAS-W0193] Task 10 — Reading/Listening evidence for the path summary, read exclusively from the Smart
 * Test snapshot (Warm-up B's Reading/Listening banding logic exists per Task 9 but is not yet wired into a
 * persisted, re-testable activity — see the Task 9/10 handoffs). {@code note} always discloses this plainly
 * (design spec §6.1: "Nếu Reading/Listening không được đánh giá lại trong Learning Path, UI phải ghi rõ
 * 'chưa được đánh giá lại trong lộ trình'") so the UI never implies this is fresh, re-verified evidence.
 * {@code hasEvidence} is false when the assessment attempt has no valid sample for this skill at all — this
 * is reported as "insufficient evidence", never coerced into a 0% score. */
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
