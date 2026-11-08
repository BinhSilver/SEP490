package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathMockStatus;
import com.jlas.learning.dto.CompleteFromMockRequest;

/** [JLAS-W0108] Bridge from the JLPT path to the Mock team's JLPT mock test (Plan 3 Task 17, policy #42–#43). */
public interface JpathMockService {

    /** The Thi thử card for {@code level} (null = the path's open level). */
    JpathMockStatus status(Long userId, String level);

    /** Applies the learner's own attempt {@code request.attemptId()}; pass/fail is read from the Mock module only. */
    JpathMockStatus complete(Long userId, CompleteFromMockRequest request);

    /** {@link #complete} with the latest attempt of {@code level}; nothing usable → just the status. */
    JpathMockStatus sync(Long userId, String level);
}
