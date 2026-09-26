package com.jlas.learning.dto;

import java.util.List;

// Internal server snapshot; never accept assessment evidence from request JSON.
public record LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
        String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
        String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
        List<WeaknessEvidence> weaknesses) { }
