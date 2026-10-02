package com.jlas.learning.dto;

/** [JLAS-W0193] Task 10 — Dialogue contributes completion status only, never a score (design spec §2.2/§2.14),
 * so the summary reports it as a plain completed/total count rather than a percentage that would imply a
 * grade. */
public record InteractiveDialogueSummary(
        int totalCount,
        int completedCount,
        boolean allCompleted
) {
}
