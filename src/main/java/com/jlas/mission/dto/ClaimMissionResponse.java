package com.jlas.mission.dto;

/** Result of a claim; {@code alreadyClaimed} is true for a repeated claim, which awards nothing. */
public record ClaimMissionResponse(String code, String title, int rewardPoints, boolean alreadyClaimed,
                                   long creditBalance) {
}
