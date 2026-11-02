package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Kanji list filter. {@code q} matches the character, Hán Việt or meaning without accents, or a reading;
 * {@code radical} = radical number; {@code sort} JLPT (default) | STROKES | FREQUENCY | HAN_VIET, missing values last;
 * {@code view} TABLE|GRID is the page's display choice and does not change the rows. {@code page} is 0-based.
 */
public record JpathKanjiFilter(String level, String q, Integer radical, String sort, String view, int page) {
}
