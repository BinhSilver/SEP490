package com.jlas.jlptpath.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * [JLAS-W0108] One character cell of the writing practice. {@code svgUrl} is null (and {@code hasSvg} false) when no
 * KanjiVG file ships for it — the cell is still traced, just without stroke-order animation. {@code strokeCount} comes
 * from the path kanji, null otherwise.
 */
public record JpathWritingChar(@JsonProperty("char") String ch, int codePoint, String svgUrl, Integer strokeCount,
                               boolean hasSvg) {
}
