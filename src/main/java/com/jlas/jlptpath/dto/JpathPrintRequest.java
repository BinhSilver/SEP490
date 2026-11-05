package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Query of the print page {@code /learning/jlpt/print}. {@code scope} TODAY | LIST | KANA; {@code tab}
 * VOCAB | KANJI (TODAY, LIST); {@code script} HIRAGANA | KATAKANA (KANA); {@code topic}, {@code state}, {@code q},
 * {@code radical}, {@code sort} are the list filters (LIST). {@code level} is the list level the page sends; blank =
 * the learner's open level.
 */
public record JpathPrintRequest(String scope, String tab, String topic, String state, String q, String script,
                                String radical, String sort, String level, String day) {
}
