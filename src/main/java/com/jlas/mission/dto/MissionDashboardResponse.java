package com.jlas.mission.dto;

import java.time.LocalDate;
import java.util.List;

/** Everything the goals page renders in one call. */
public record MissionDashboardResponse(
        LocalDate today,
        String zoneId,
        long creditBalance,
        DailyGoalProgress dailyGoal,
        StreakResponse streak,
        List<MissionItemResponse> missions,
        int pendingRewards,
        long earnedToday,
        int dailyEarnCap) {
}
