package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One side of a confusable pair: a word (reading) or a kanji (level readings joined with "・"). */
public record JpathConfusableSide(String code, String expression, String reading, String meaning) {
}
