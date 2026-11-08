package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] A locked question of a check, for redrawing the page: the learner's value, the verdict and the
 * correct value. Boards carry {@code matchedPairs} ([leftId, rightId]) and {@code errors} instead of a value.
 */
public record JpathAnsweredView(String questionId, String value, boolean correct, String correctValue,
                                String explanation, List<List<String>> matchedPairs, int errors,
                                JpathReportTargetView report) {
}
