package com.jlas.learning.dto;

import java.time.LocalDateTime;

/** [JLAS-W0193] Task 10 — one genuinely attempted-but-not-yet-passed micro-check batch, surfaced by the
 * path summary so a persistently weak skill/target is named explicitly instead of being averaged away by
 * a healthy-looking overall score (design spec §6.1). Only batches with at least one valid submit are
 * reported here — an activity never reached is simply not attempted yet, not "weak". */
public record InteractiveUnresolvedBatch(
        Long activityId,
        Integer batchIndex,
        Integer latestScore,
        boolean reviewRequired,
        LocalDateTime cooldownUntil
) {
}
