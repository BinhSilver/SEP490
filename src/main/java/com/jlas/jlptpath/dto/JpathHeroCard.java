package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One card of the "Hôm nay" hero strip: the next new word, or a review card on skill/review days. */
public record JpathHeroCard(String itemCode, String expression, String reading, String meaning) {
}
