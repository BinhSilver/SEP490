package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;

/** [JLAS-W0193] Task 7 — result of a heartbeat or reached-bottom submission. Every field is server-computed;
 * there is no client-settable {@code completed}/{@code accumulatedSeconds} anywhere in the request DTOs.
 * Communication dialogues (owner 2026-10-19, §2.24) also report their "Từ & cụm từ" flip cards. */
public record DialogueProgressResult(
        Long activityId,
        LearningActivityState activityState,
        int requiredSeconds,
        int accumulatedSeconds,
        int remainingSeconds,
        boolean reachedBottom,
        boolean completed,
        boolean lineByLinePractice,
        int practicedLines,
        int totalLines,
        int flippedPhrases,
        int totalPhrases
) {
    /** JLPT dialogues report timed evidence only. */
    public DialogueProgressResult(Long activityId, LearningActivityState activityState, int requiredSeconds,
            int accumulatedSeconds, int remainingSeconds, boolean reachedBottom, boolean completed) {
        this(activityId, activityState, requiredSeconds, accumulatedSeconds, remainingSeconds, reachedBottom,
                completed, false, 0, 0, 0, 0);
    }

    /** Communication dialogues without phrase cards. */
    public DialogueProgressResult(Long activityId, LearningActivityState activityState, int requiredSeconds,
            int accumulatedSeconds, int remainingSeconds, boolean reachedBottom, boolean completed,
            boolean lineByLinePractice, int practicedLines, int totalLines) {
        this(activityId, activityState, requiredSeconds, accumulatedSeconds, remainingSeconds, reachedBottom,
                completed, lineByLinePractice, practicedLines, totalLines, 0, 0);
    }
}
