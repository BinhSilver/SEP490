package com.jlas.learning.dto;

/** Canonical published vocabulary shown as read-only reference when a lesson has fewer than five words. */
public record ReferenceVocabularyResponse(
        Long canonicalId,
        String expression,
        String reading,
        String meaning,
        String wordType) {
}
