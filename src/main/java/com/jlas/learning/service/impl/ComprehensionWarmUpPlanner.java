package com.jlas.learning.service.impl;

import com.jlas.learning.dto.SkillScore;

import java.util.ArrayList;
import java.util.List;

/**
 * [JLAS-W0193] Task 9 — Reading/Listening warm-up banding + daily packing (design spec §5.4). This is
 * pure, deterministic decision logic: given a skill's Smart Test correctness, how many warm-up lessons
 * of that skill are warranted, and how those lessons pack into days under the "at most two Reading +
 * Listening combined per day" cap and the learner's minutes-per-day budget.
 *
 * <p><b>Deliberately not wired into persisted V2 activities this session</b> — {@code LearningActivityType}
 * has no {@code READING}/{@code LISTENING} value yet, and adding one requires a new
 * {@code LearningMemberType}, schema, and dedicated practice/micro-check pages (a slice comparable in
 * size to Task 4 or Task 6 by itself). See the Task 9 completion handoff for the explicit scope decision.
 * This class exists so the banding/packing rules themselves are real, tested, ready-to-wire code rather
 * than left undesigned.</p>
 */
public final class ComprehensionWarmUpPlanner {
    private ComprehensionWarmUpPlanner() { }

    /** One skill's warm-up banding decision. {@code needsVerification} is true when the evidence is
     * missing/insufficient (design spec: "generate a verification step instead of concluding weak"), in
     * which case {@code lessonCount} is always 0 — a verification signal is never silently treated as 0%
     * or as a concluded weakness. */
    public record ComprehensionBand(String skill, int lessonCount, boolean needsVerification) {
    }

    /** Reuses {@link WeakTagPolicy#comprehensionLessonCount(SkillScore)} — the exact same approved 0/4/6/8
     * banding boundary function Learning Path V1 already uses (rules doc §2.10/§5 "P3"), rather than
     * re-deriving a second copy of the 40/50/70 thresholds for V2. */
    public static ComprehensionBand band(SkillScore score) {
        int count = WeakTagPolicy.comprehensionLessonCount(score);
        return count < 0
                ? new ComprehensionBand(score.skill(), 0, true)
                : new ComprehensionBand(score.skill(), count, false);
    }

    /** One day's Reading/Listening warm-up allocation. */
    public record DayAllocation(int dayOffset, int readingCount, int listeningCount) {
        public int total() {
            return readingCount + listeningCount;
        }
    }

    /**
     * Packs {@code readingCount} + {@code listeningCount} lessons into days, each day capped at
     * {@code maxPerDay} (design spec §5.4: "tối đa hai bài Reading/Listening tổng cộng mỗi ngày") AND at
     * {@code minutesPerDay} using {@code minutesPerLesson} as the (documented-assumption) per-lesson time
     * estimate — mirroring the existing V1 precedent that a single resource longer than the remaining
     * budget still fits its own day rather than being split (rules doc §2.4). Interleaves Reading and
     * Listening within a day (alternating) so a day is not needlessly Reading-only when both remain,
     * without exceeding either cap. Deterministic: identical inputs always produce identical output, with
     * no randomness anywhere in the packing order.
     */
    public static List<DayAllocation> packIntoDays(int readingCount, int listeningCount, int maxPerDay,
            int minutesPerDay, int minutesPerLesson) {
        if (maxPerDay <= 0) throw new IllegalArgumentException("maxPerDay must be positive.");
        if (minutesPerLesson <= 0) throw new IllegalArgumentException("minutesPerLesson must be positive.");
        List<DayAllocation> days = new ArrayList<>();
        int remainingReading = Math.max(0, readingCount);
        int remainingListening = Math.max(0, listeningCount);
        int dayOffset = 0;
        while (remainingReading > 0 || remainingListening > 0) {
            dayOffset++;
            int perDayCap = maxPerDay;
            if (minutesPerDay > 0) {
                int byBudget = Math.max(1, minutesPerDay / minutesPerLesson);
                perDayCap = Math.min(perDayCap, byBudget);
            }
            int reading = 0;
            int listening = 0;
            boolean takeReadingFirst = true;
            while (reading + listening < perDayCap && (remainingReading > 0 || remainingListening > 0)) {
                if (takeReadingFirst && remainingReading > 0) {
                    reading++;
                    remainingReading--;
                } else if (remainingListening > 0) {
                    listening++;
                    remainingListening--;
                } else if (remainingReading > 0) {
                    reading++;
                    remainingReading--;
                } else {
                    break;
                }
                takeReadingFirst = !takeReadingFirst;
            }
            days.add(new DayAllocation(dayOffset, reading, listening));
        }
        return days;
    }
}
