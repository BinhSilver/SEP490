package com.jlas.mission.dto;

/** Aggregated mission summary rendered in the learner dashboard widget. */
public record MissionSummaryResponse(
        int creditBalance,
        int currentStreak,
        int longestStreak,
        DailyGoalProgress dailyGoal) {
}
