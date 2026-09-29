package com.jlas.mission.dto;

import java.time.LocalDateTime;

/**
 * A reward the learner has not seen animated yet.
 *
 * @param kind DAILY_GOAL, STREAK_EXTENDED, STREAK_MILESTONE or MISSION_READY
 */
public record CelebrationResponse(Long id, String kind, String title, int amount, Integer streak,
                                  LocalDateTime createdAt) {
}
