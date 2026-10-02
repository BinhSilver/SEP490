package com.jlas.mission.dto;

/** Streak after a freeze decision, plus the balance in case credits were spent. */
public record FreezeActionResponse(StreakResponse streak, long creditBalance, int creditsSpent) {
}
