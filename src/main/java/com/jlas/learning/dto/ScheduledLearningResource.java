package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningPhase;
import java.util.Set;

/** A selected resource positioned into a daily schedule. */
public record ScheduledLearningResource(LearningResourceCandidate resource, LearningPhase phase,
        String reasonCode, String reasonText, Set<String> matchedTagCodes,
        int dayNumber, int orderIndex, int estimatedMinutes) {
    public ScheduledLearningResource {
        matchedTagCodes = Set.copyOf(matchedTagCodes);
    }
}
