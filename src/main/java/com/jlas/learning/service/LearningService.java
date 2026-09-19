package com.jlas.learning.service;

import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningPathResponse;

/** Orchestrates two-phase learning path creation and reading. */
public interface LearningService {
    /** Creates a path for a finalized assessment attempt, or returns the existing one on retry. */
    CreateLearningPathResult create(Long userId, Long assessmentAttemptId);

    /** Returns the user's active path; empty state is NOT_FOUND. */
    LearningPathResponse current(Long userId);
}
