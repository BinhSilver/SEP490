package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Verdict of one locked question. {@code correctValue}: option id (MCQ), piece ids (ORDER) or the
 * answer text (FILL_TEXT); {@code compare} only when the picked option is the answer's confusable partner.
 */
public record JpathAnswerResult(boolean correct, String correctValue, String explanation, JpathCompareView compare,
                                JpathReportTargetView report) {
}
