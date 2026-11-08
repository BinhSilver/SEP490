package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Notice shown with a pace estimate: {@code code} is EXAM_DATE_PASSED, LATE, OVER_BUDGET or PRIORITY_MODE;
 * {@code messageVi} is always shown.
 */
public record JpathWarningView(String code, String messageVi) {
}
