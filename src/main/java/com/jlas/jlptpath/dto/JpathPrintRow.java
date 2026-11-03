package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] One printed row. {@code kind} VOCAB | KANJI | KANA; KANJI rows carry Hán Việt (upper case), the level
 * readings joined by {@code ・} and the stroke-order SVG; VOCAB rows have no SVG.
 */
public record JpathPrintRow(String kind, String text, String reading, String hanViet, String meaning,
                            String strokeSvgUrl, Integer strokeCount, JpathPrintCells cells) {

    /** The characters written in one copy: the first {@code cells.units()} non-blank code points of {@code text}. */
    public List<String> chars() {
        if (text == null) {
            return List.of();
        }
        int units = cells == null ? Integer.MAX_VALUE : cells.units();
        return text.codePoints().filter(cp -> !Character.isWhitespace(cp)).limit(units)
                .mapToObj(Character::toString).toList();
    }
}
