package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Query of the print page {@code /learning/jlpt/print}. {@code scope} TODAY | LIST | KANA; {@code tab}
 * VOCAB | KANJI (TODAY, LIST); {@code script} HIRAGANA | KATAKANA (KANA); {@code topic}, {@code state}, {@code q},
 * {@code radical}, {@code sort} are the list filters (LIST). {@code level} is the list level the page sends; blank =
 * the learner's open level. F226 (owner 22/11): scope PART prints one whole study part — {@code part} its number on the
 * map, {@code tab} VOCAB | KANJI | KANA (KANA = a part of the kana stage).
 */
public record JpathPrintRequest(String scope, String tab, String topic, String state, String q, String script,
                                String radical, String sort, String level, String day, String part) {

    public JpathPrintRequest(String scope, String tab, String topic, String state, String q, String script,
                             String radical, String sort, String level, String day) {
        this(scope, tab, topic, state, q, script, radical, sort, level, day, null);
    }
}
