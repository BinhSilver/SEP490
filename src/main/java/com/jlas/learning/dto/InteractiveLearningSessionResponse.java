package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningSessionState;
import com.jlas.learning.enums.LearningSessionType;

import java.util.List;
import java.util.Map;

/** Server-owned roadmap session projection. `allowedActions` is authoritative for the UI. */
public record InteractiveLearningSessionResponse(
        LearningSessionType sessionType,
        LearningSessionState state,
        List<InteractiveLearningActivityResponse> activities,
        List<String> allowedActions,
        Map<String, Integer> contentCounts,
        int contentCount,
        int estimatedMinutes,
        List<ReferenceVocabularyResponse> referenceVocabulary,
        String blockedReason) {
    public InteractiveLearningSessionResponse {
        activities = activities == null ? List.of() : List.copyOf(activities);
        allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        contentCounts = contentCounts == null ? Map.of() : Map.copyOf(contentCounts);
        referenceVocabulary = referenceVocabulary == null ? List.of() : List.copyOf(referenceVocabulary);
    }
}
