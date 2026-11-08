package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] One CJK character of a word. A character with no visible path kanji still has {@code character} and
 * {@code svgUrl}; the other fields are then null.
 */
public record JpathKanjiInWordView(String character, String code, String hanViet, String meaning, String svgUrl,
                                   Integer strokeCount) {
}
