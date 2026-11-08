package com.jlas.learning.dto;

/**
 * F1-3 / R1 (owner 2026-10-27): what a path would be for these entry answers, from a full trial composition that is
 * rolled back — {@code estimatedDays} is its last planned day (warm-up included), {@code packages} its package count.
 */
public record LearningPathEstimate(Integer estimatedDays, int packages, Integer minutesPerDay) { }
