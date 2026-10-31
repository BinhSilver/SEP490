package com.jlas.learning.dto;

/**
 * F1-6 (owner 2026-10-28): how long the learner really takes for the path's finished parts, against their estimates.
 * {@code suggestion} is LIGHTER, FASTER or null; it is only a suggestion — the learner changes pace themselves through
 * the existing pace preview. {@code percentOfEstimate} is null until there is enough evidence.
 */
public record LearningPaceMeasurement(int measuredPackages, int studyDays, int estimatedMinutes, int actualMinutes,
        Integer percentOfEstimate, int minutesPerDay, String suggestion, Integer suggestedMinutesPerDay) { }
