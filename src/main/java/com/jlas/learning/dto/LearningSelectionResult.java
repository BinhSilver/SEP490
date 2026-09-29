package com.jlas.learning.dto;

import java.util.List;

public record LearningSelectionResult(List<SelectedLearningResource> resources,
        List<CoverageGap> coverageGaps, String policyVersion) {
    public LearningSelectionResult {
        resources = List.copyOf(resources);
        coverageGaps = List.copyOf(coverageGaps);
    }
}
