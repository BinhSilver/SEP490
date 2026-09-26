package com.jlas.learning.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read model of the user's active learning path: two phases with daily scheduling,
 * coverage gaps and overall totals.
 */
public record LearningPathResponse(Long id, Long userId, Long assessmentAttemptId, String goal, String targetLevel,
        String currentLevel, Integer minutesPerDay, String status, int version, LocalDateTime createdAt,
        List<LearningPhaseGroup> phases, List<CoverageGap> coverageGaps,
        int totalLessons, int totalMinutes, int totalDays, int completedLessons,
        boolean warmUpCompleted, boolean gatePassed) {
    public LearningPathResponse {
        phases = List.copyOf(phases);
        coverageGaps = List.copyOf(coverageGaps);
    }
}
