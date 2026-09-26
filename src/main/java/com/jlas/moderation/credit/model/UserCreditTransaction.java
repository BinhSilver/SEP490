package com.jlas.moderation.credit.model;

/** Compatibility contract for credit reasons used by migrated features. */
public final class UserCreditTransaction {
    private UserCreditTransaction() { }

    public enum CreditReason {
        CREATOR_REWARD,
        DAILY_CHECKIN,
        STUDY_REWARD,
        QUIZ_REWARD,
        SHADOWING_REWARD,
        STREAK_MILESTONE_REWARD,
        DAILY_GOAL_REWARD,
        STREAK_FREEZE,
        VIDEO_UNLOCK,
        ADMIN_GRANT,
        WORD_CONTRIBUTION_REWARD,
        REFUND
    }
}
