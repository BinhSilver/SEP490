package com.jlas.jlptpath.dto;

/** [JLAS-W0108] A confusable pair both sides of which the learner has learned; wrong check answers on either side. */
public record JpathConfusablePairView(String itemType, JpathConfusableSide a, JpathConfusableSide b, int wrongCount) {
}
