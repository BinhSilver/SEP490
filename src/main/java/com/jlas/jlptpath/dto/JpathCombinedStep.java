package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] One step of the combined (Gộp) day: key is REVIEW, KANA, VOCAB, KANJI, GRAMMAR or SKILL_QUIZ;
 * {@code active} marks the first step not complete.
 */
public record JpathCombinedStep(String key, int done, int total, boolean complete, boolean active) {
}
