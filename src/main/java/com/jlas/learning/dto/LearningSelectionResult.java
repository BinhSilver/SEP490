package com.jlas.learning.dto;

import java.util.List;

public record LearningSelectionResult(List<SelectedLearningResource> resources,
        List<CoverageGap> coverageGaps, String policyVersion, List<WeaknessEvidence> weakEvidence) {
    public LearningSelectionResult {
        resources = List.copyOf(resources);
        coverageGaps = List.copyOf(coverageGaps);
        weakEvidence = List.copyOf(weakEvidence);
    }
}
