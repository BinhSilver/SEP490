package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Vocabulary list filter. {@code topic} = topic key, {@code state} = NEW|LEARNING|NOTEBOOK|KNOWN (blank or
 * ALL = every state); {@code q} matches the kana-normalized expression/reading, the lowercase romaji, or the meaning
 * without Vietnamese accents. {@code page} is 0-based.
 */
public record JpathVocabFilter(String level, String topic, String state, String q, int page) {
}
