package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningPaceMeasurement;
import com.jlas.learning.entity.LearningPathPackage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F1-6 (owner 2026-10-28): measures real study time from finished packages (start → finish) and suggests a pace step
 * when it differs from the estimates by more than {@link #DEVIATION_PERCENT}% after {@link #MIN_STUDY_DAYS} study days.
 * A package started one day and finished another, or open longer than {@link #MAX_SITTING_MINUTES}, measures a break,
 * not study, and is left out. Faster than estimated → FASTER (more each day); slower → LIGHTER. Never changes the path.
 */
final class LearningPaceMeasurementPolicy {
    static final int MIN_STUDY_DAYS = 5;
    static final int DEVIATION_PERCENT = 25;
    static final int MAX_SITTING_MINUTES = 240;

    private LearningPaceMeasurementPolicy() { }

    static LearningPaceMeasurement measure(List<LearningPathPackage> packages, int minutesPerDay) {
        int measured = 0, estimated = 0, actual = 0;
        Set<LocalDate> days = new HashSet<>();
        for (LearningPathPackage pkg : packages) {
            if (pkg.getStartedAt() == null || pkg.getCompletedAt() == null || pkg.getEstimatedMinutes() == null
                    || pkg.getEstimatedMinutes() <= 0) continue;
            if (!pkg.getStartedAt().toLocalDate().equals(pkg.getCompletedAt().toLocalDate())) continue;
            long minutes = Duration.between(pkg.getStartedAt(), pkg.getCompletedAt()).toMinutes();
            if (minutes <= 0 || minutes > MAX_SITTING_MINUTES) continue;
            measured++;
            estimated += pkg.getEstimatedMinutes();
            actual += (int) minutes;
            days.add(pkg.getCompletedAt().toLocalDate());
        }
        if (days.size() < MIN_STUDY_DAYS || estimated == 0) {
            return new LearningPaceMeasurement(measured, days.size(), estimated, actual, null, minutesPerDay, null, null);
        }
        int percent = Math.round(actual * 100f / estimated);
        String direction = percent <= 100 - DEVIATION_PERCENT ? "FASTER" : percent >= 100 + DEVIATION_PERCENT ? "LIGHTER" : null;
        Integer next = direction == null ? null : InteractivePaceReplanServiceImpl.nextPreset(minutesPerDay, direction);
        if (next != null && next == minutesPerDay) { direction = null; next = null; } // already at the last preset
        return new LearningPaceMeasurement(measured, days.size(), estimated, actual, percent, minutesPerDay, direction, next);
    }
}
