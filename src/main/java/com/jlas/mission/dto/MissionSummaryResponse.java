package com.jlas.mission.dto;

import java.util.List;

/** Aggregated mission summary rendered in the learner dashboard widget. */
public record MissionSummaryResponse(
        long creditBalance,
        int currentStreak,
        int longestStreak,
        DailyGoalProgress dailyGoal,
        List<StreakDayResponse> week) {
}
