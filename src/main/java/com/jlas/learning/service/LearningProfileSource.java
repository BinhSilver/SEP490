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

    /**
     * F1-4 (owner 2026-10-28): the words (vocabulary entry ids) answered right in a full Smart Test batch of this attempt.
     * Empty by default (adapters without Smart Test answers).
     */
    default java.util.Set<Long> knownVocabularyIds(Long assessmentAttemptId) {
        return java.util.Set.of();
    }

    /** F1-5 (owner 2026-10-28): the survey's focus skills (VOCABULARY, READING, LISTENING); empty by default. */
    default java.util.List<String> focusSkills(Long userId) {
        return java.util.List.of();
    }
}
