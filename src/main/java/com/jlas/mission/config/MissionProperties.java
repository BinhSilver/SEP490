package com.jlas.mission.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable rules for daily goal, missions, streak freeze and reward retries.
 *
 * @param defaultZone              timezone used until the learner's browser reports its own
 * @param dailyGoalReward          credits granted once per day when the daily goal is met
 * @param freezePrice              credits charged to buy a freeze while a streak is at risk
 * @param maxFreezes               freezes a learner can hold at once
 * @param freezeBonusFromMilestone streak milestones of at least this many days also grant one freeze
 * @param awardRetryDelayMs        how often failed credit awards are retried
 * @param devTools                 enables the demo endpoints under /api/dev/mission (never in production)
 */
@ConfigurationProperties(prefix = "mission")
public record MissionProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") String defaultZone,
        @DefaultValue("10") int dailyGoalReward,
        @DefaultValue("50") int freezePrice,
        @DefaultValue("2") int maxFreezes,
        @DefaultValue("7") int freezeBonusFromMilestone,
        @DefaultValue("60000") long awardRetryDelayMs,
        @DefaultValue("false") boolean devTools) {
}
