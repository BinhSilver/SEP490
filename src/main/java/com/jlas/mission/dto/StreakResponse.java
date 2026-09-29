package com.jlas.mission.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Streak state in the learner's timezone.
 *
 * @param status NONE (no streak), ACTIVE (alive, today not done yet), DONE_TODAY, or AT_RISK (freeze offer open)
 */
public record StreakResponse(
        int currentStreak,
        int longestStreak,
        String status,
        LocalDate lastCompletedDate,
        int freezeCount,
        int maxFreezes,
        FreezeOfferResponse freezeOffer,
        Integer nextMilestoneDays,
        Integer nextMilestoneReward,
        List<StreakDayResponse> week) {
}
