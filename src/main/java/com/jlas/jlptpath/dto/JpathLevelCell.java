package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathLevelCellState;

/**
 * [JLAS-W0108] One level on the level bar. {@code learned}/{@code total} are vocabulary counts for display;
 * {@code percent} is the level % (mean of the vocabulary, kanji and grammar %, policy #16).
 */
public record JpathLevelCell(String level, JpathLevelCellState state, int learned, int total, int percent,
                             String note) {
}
