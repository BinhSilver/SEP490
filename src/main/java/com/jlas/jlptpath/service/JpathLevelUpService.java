package com.jlas.jlptpath.service;

import com.jlas.assessment.dto.JlptMockResultResponse;

/**
 * [JLAS-W0108] Applies a JLPT mock attempt to the learner's path (Plan 3 Task 18): records it once in jpath_mock_result
 * (key = attempt id; a repeat is a no-op), counts the mock day, and on PASSED opens the next released level (#42, #44,
 * #45). Callers ({@code JpathMockService}) have already checked ownership, level and opened_at.
 */
public interface JpathLevelUpService {

    void apply(Long userId, JlptMockResultResponse result);
}
