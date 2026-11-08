package com.jlas.learning.service;

import com.jlas.learning.dto.LearningPacePreviewResponse;
import com.jlas.learning.dto.LearningPaceRequest;

/** [JLAS-W0108] Task 10 - change minutes per day for the ACTIVE interactive path without touching package or
 * member identity, completion state or the target level. */
public interface InteractivePaceReplanService {
    LearningPacePreviewResponse preview(Long userId, String direction);

    /** Applies a previewed change; retrying an already-applied request returns {@code changed=false}. */
    LearningPacePreviewResponse apply(Long userId, LearningPaceRequest request);

    /** F1-6 (owner 2026-10-28): real study time of finished parts against their estimates, with a pace suggestion. */
    com.jlas.learning.dto.LearningPaceMeasurement measure(Long userId);
}
