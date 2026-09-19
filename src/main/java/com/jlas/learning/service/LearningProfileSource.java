package com.jlas.learning.service;

import com.jlas.learning.dto.LearningProfileSnapshot;

public interface LearningProfileSource {
    // Implementations verify ownership and load persisted survey and finalized tag results.
    LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId);
}
