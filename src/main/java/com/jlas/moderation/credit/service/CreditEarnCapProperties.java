package com.jlas.moderation.credit.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Daily cap on CP earned from studying (kế hoạch Pro/CP, mục "Kiếm CP").
 * Only {@code cappedReasons} count; purchases, refunds, admin grants, streak milestones and creator rewards never do.
 *
 * @param freeDaily            CP a free account can earn from capped reasons per day
 * @param proDaily             CP an active Pro account can earn per day
 * @param cappedReasons        {@code CreditReason} names that count toward the cap
 * @param exemptReferenceTypes one-time rewards that share a capped reason (chapter completion, dialogue challenge)
 */
@ConfigurationProperties(prefix = "credit.earn-cap")
public record CreditEarnCapProperties(
        @DefaultValue("40") int freeDaily,
        @DefaultValue("50") int proDaily,
        @DefaultValue({"DAILY_CHECKIN", "STUDY_REWARD", "QUIZ_REWARD", "SHADOWING_REWARD", "DAILY_GOAL_REWARD"})
        List<String> cappedReasons,
        @DefaultValue({"SOURCE_LESSON", "PACKAGE", "COMM_CHALLENGE"}) List<String> exemptReferenceTypes) {
}
