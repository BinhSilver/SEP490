package com.jlas.learning.service;

import com.jlas.learning.dto.LearningProfileSnapshot;

public interface LearningProfileSource {
    // Implementations verify ownership and load persisted survey and finalized tag results.
    LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId);

    /**
     * [JLAS-W0108] Build a from-zero profile from a completed survey whose learner chose
     * "chưa từng học tiếng Nhật" (self-reported FROM_ZERO): no Smart Test is required, the
     * assessed level is BELOW_N5 and the path starts with the kana foundation. Default throws so
     * catalog-less adapters (unit tests) stay unchanged; the production source overrides it.
     */
    default LearningProfileSnapshot loadFromSurvey(Long userId) {
        throw new UnsupportedOperationException("From-zero survey profiles are not supported by this source.");
    }
}
