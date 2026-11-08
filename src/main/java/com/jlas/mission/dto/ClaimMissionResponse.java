package com.jlas.mission.dto;

/**
 * Result of a claim; {@code alreadyClaimed} is true for a repeated claim, which awards nothing.
 * {@code rewardPoints} is what was actually added; {@code capReached} is true when today's earn cap cut it.
 */
public record ClaimMissionResponse(String code, String title, int rewardPoints, boolean alreadyClaimed,
                                   long creditBalance, boolean capReached) {
    public ClaimMissionResponse(String code, String title, int rewardPoints, boolean alreadyClaimed,
                                long creditBalance) {
        this(code, title, rewardPoints, alreadyClaimed, creditBalance, false);
    }
}
