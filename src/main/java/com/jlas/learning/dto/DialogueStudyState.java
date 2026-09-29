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
        List<DialogueLineView> lines
) {
    public DialogueStudyState {
        lines = List.copyOf(lines);
    }

    /** One line of the canonical dialogue script, read-only. Only fields with real, approved data are ever
     * populated — {@code audioUrl} is {@code null} (never fabricated) when no approved audio exists for this
     * line, and {@code roleName}/{@code sceneCode}/{@code bubbleSide} are {@code null} when the canonical
     * {@code dialogue_line_detail} row has no value for them. */
    public record DialogueLineView(
            Long lineId,
            int orderIndex,
            String roleCode,
            String roleName,
            String sceneCode,
            String bubbleSide,
            String japanese,
            String meaningVi,
            String audioUrl
    ) { }
}
