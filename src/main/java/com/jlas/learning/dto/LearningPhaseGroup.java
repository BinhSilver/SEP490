package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningPhase;
import java.util.List;

/** A contiguous phase (WARM_UP or GOAL_LEARNING) with its scheduled items and totals. */
public record LearningPhaseGroup(LearningPhase phase, List<LearningPathItemDto> items,
        int lessonCount, int totalMinutes, int dayCount) {
    public LearningPhaseGroup {
        items = List.copyOf(items);
    }
}
