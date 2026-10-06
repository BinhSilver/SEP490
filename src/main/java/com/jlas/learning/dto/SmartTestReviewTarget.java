package com.jlas.learning.dto;

/**
 * A Smart Test item kept visible for read-only review: a mapped vocabulary/kanji item when a skill has fewer than
 * five targets, or a wrong grammar question that has no grammar lesson yet ({@code sentence}/{@code answer}/
 * {@code explanation} come straight from that question; null for vocabulary/kanji).
 */
public record SmartTestReviewTarget(String skill, String target, String sentence, String answer, String explanation) {
    public SmartTestReviewTarget(String skill, String target) {
        this(skill, target, null, null, null);
    }
}
