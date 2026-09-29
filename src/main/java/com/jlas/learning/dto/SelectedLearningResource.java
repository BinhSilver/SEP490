package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningPhase;
import java.util.Set;

public record SelectedLearningResource(LearningResourceCandidate resource, LearningPhase phase,
        String reasonCode, String reasonText, Set<String> matchedTagCodes) {
    public SelectedLearningResource {
        matchedTagCodes = Set.copyOf(matchedTagCodes);
    }
}
