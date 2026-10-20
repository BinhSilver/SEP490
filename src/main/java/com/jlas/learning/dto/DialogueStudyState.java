package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import java.util.List;

/** [JLAS-W0193] Task 7 — server-derived Dialogue study state: the canonical, read-only dialogue script plus
 * server-owned completion evidence. {@code accumulatedSeconds}/{@code remainingSeconds}/{@code reachedBottom}/
 * {@code completed} are never derived from anything the client reports — only from
 * {@code learning_dialogue_progress}, updated exclusively by the heartbeat and reached-bottom endpoints. */
public record DialogueStudyState(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int requiredSeconds,
        int accumulatedSeconds,
        int remainingSeconds,
        boolean reachedBottom,
        boolean completed,
        List<DialogueLineView> lines,
        boolean lineByLinePractice,
        List<Long> practicedLineIds,
        DialogueAids aids
) {
    public DialogueStudyState {
        lines = List.copyOf(lines);
        practicedLineIds = practicedLineIds == null ? List.of() : List.copyOf(practicedLineIds);
    }

    /** No communication aids (JLPT dialogues, or a lesson without an aid row). */
    public DialogueStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int requiredSeconds, int accumulatedSeconds, int remainingSeconds,
            boolean reachedBottom, boolean completed, List<DialogueLineView> lines, boolean lineByLinePractice,
            List<Long> practicedLineIds) {
        this(activityId, activityType, activityState, sourceRevision, requiredSeconds, accumulatedSeconds,
                remainingSeconds, reachedBottom, completed, lines, lineByLinePractice, practicedLineIds, null);
    }

    /** JLPT dialogues: timed evidence only, no line-by-line practice (owner 2026-10-19 keeps 180s there). */
    public DialogueStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int requiredSeconds, int accumulatedSeconds, int remainingSeconds,
            boolean reachedBottom, boolean completed, List<DialogueLineView> lines) {
        this(activityId, activityType, activityState, sourceRevision, requiredSeconds, accumulatedSeconds,
                remainingSeconds, reachedBottom, completed, lines, false, List.of());
    }

    /** One line of the canonical dialogue script, read-only. {@code audioUrl} is {@code null} when no
     * playback source is configured, a safe HTTP/relative URL for a recorded file, or the explicit
     * {@code tts:ja-JP} sentinel for browser-synthesized Japanese speech. Role and scene fields remain null
     * when the canonical {@code dialogue_line_detail} row has no value for them. */
    public record DialogueLineView(
            Long lineId,
            int orderIndex,
            String roleCode,
            String roleName,
            String sceneCode,
            String bubbleSide,
            String japanese,
            String meaningVi,
            String audioUrl,
            String furigana
    ) {
        /** A line without furigana. */
        public DialogueLineView(Long lineId, int orderIndex, String roleCode, String roleName, String sceneCode,
                String bubbleSide, String japanese, String meaningVi, String audioUrl) {
            this(lineId, orderIndex, roleCode, roleName, sceneCode, bubbleSide, japanese, meaningVi, audioUrl, null);
        }
    }

    /** Owner 2026-10-19 (C2/C4): J-LAS supplement study aids of a communication dialogue. {@code furigana} on each
     * line uses {@code {漢字|かな}} groups and is only attached when its base text equals that line; phrases point at
     * the first line containing them ({@code lineId} null when none does); patterns are the lesson's applied
     * grammar points for the end summary. */
    public record DialogueAids(String label, String reviewStatus, List<Phrase> phrases, List<KeyPattern> patterns) {
        public DialogueAids {
            phrases = phrases == null ? List.of() : List.copyOf(phrases);
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
        }

        /** One "Từ & cụm từ" flip card (§2.24); {@code index} is its stable position, {@code flipped} server state. */
        public record Phrase(int index, String japanese, String reading, String meaningVi, Long lineId, boolean flipped) { }

        public record KeyPattern(String pattern, String meaningVi, String note) { }
    }
}
