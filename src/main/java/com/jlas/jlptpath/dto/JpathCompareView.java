package com.jlas.jlptpath.dto;

/** [JLAS-W0108] Side-by-side view when the learner picked the easily confused partner: a = answer, b = picked. */
public record JpathCompareView(JpathCompoundView a, JpathCompoundView b) {
}
