package com.jlas.learning.service;

import com.jlas.learning.dto.PathCompletionResponse;

public interface LearningPathCompletionService {
    /** Completes the caller's ACTIVE interactive path once a qualifying Mock Test attempt is verified. */
    PathCompletionResponse completeFromMock(Long userId, Long attemptId);
}
