package com.jlas.jlptpath.dto;

/** [JLAS-W0108] A word shown next to an item (kanji compound or confusable partner); learned = counted or KNOWN. */
public record JpathCompoundView(String code, String expression, String reading, String meaning, boolean learned) {
}
