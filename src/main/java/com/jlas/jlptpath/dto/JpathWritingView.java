package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Writing practice of one word, kanji or kana: the text to write ({@code word}), its reading (romaji for
 * kana), meaning, Hán Việt in upper case (kanji only) and one cell per non-blank character.
 */
public record JpathWritingView(String itemType, String code, String word, String reading, String meaning,
                               String hanViet, List<JpathWritingChar> characters) {
}
