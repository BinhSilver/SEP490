package com.jlas.learning.dto;

public record GoalSurveySubmissionResponse(
        Long submissionId,
        String goalCode,
        int version,
        String learningMode,
        String assessmentRequirement,
        boolean competencyEvidence
) {}
