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
        ADMIN_DEDUCT,
        CREDIT_PURCHASE,
        WORD_CONTRIBUTION_REWARD,
        REFUND,
        /** A dictionary approval was revised to rejected; the reward is taken back. */
        WORD_CONTRIBUTION_REVOKED,
        GAME_ITEM_PURCHASE,
        /** Một lượt AI Call trả bằng CP sau khi hết hạn mức (JLAS-W0199); lỗi AI thì hoàn bằng REFUND. */
        AI_CALL_TURN
    }
}
