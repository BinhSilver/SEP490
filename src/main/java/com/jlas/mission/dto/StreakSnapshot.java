package com.jlas.mission.dto;

/** Read model for the learner's current streak. */
public record StreakSnapshot(int currentStreak, int longestStreak) {
}
