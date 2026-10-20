package com.jlas.learning.dto;

/** [JLAS-W0108] Task 10 - read-only preview of a pace change (nothing persisted). {@code changed} is false
 * when the preset is already in effect or at the lowest/highest step. */
public record LearningPacePreviewResponse(
        String direction,
        int currentMinutesPerDay,
        int newMinutesPerDay,
        boolean changed,
        int remainingPackages,
        int currentRemainingDays,
        int newRemainingDays,
        String previewToken) { }
