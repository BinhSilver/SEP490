package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;

/** [JLAS-W0108] New items per day for the three slider tabs (vocabulary, kanji, grammar). */
public record JpathSliderValues(int vocab, int kanji, int grammar) {

    /** Value for a slider tab; KANA and SKILL have no slider. */
    public int get(JpathTab tab) {
        return switch (tab) {
            case VOCAB -> vocab;
            case KANJI -> kanji;
            case GRAMMAR -> grammar;
            default -> throw new IllegalArgumentException("No slider for tab " + tab);
        };
    }
}
