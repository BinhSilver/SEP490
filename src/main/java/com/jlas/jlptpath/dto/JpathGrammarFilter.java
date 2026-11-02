package com.jlas.jlptpath.dto;

/** [JLAS-W0108] Grammar list filter: {@code unit} = one unit number or null for all, {@code q} matches pattern or meaning. */
public record JpathGrammarFilter(String level, Integer unit, String q) {
}
