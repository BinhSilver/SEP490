package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Verdict of one locked question. {@code correctValue}: option id (MCQ), piece ids (ORDER) or the
 * answer text (FILL_TEXT); {@code compare} only when the picked option is the answer's confusable partner.
 * Probe L2 (15/11): a replay of an already locked question carries {@code lockedValue} (the answer kept — e.g. from
 * another tab) and {@code checkClosed} when the check is no longer open, so the page draws the kept answer.
 */
public record JpathAnswerResult(boolean correct, String correctValue, String explanation, JpathCompareView compare,
                                JpathReportTargetView report, String lockedValue, boolean checkClosed) {

    /** A fresh verdict: no earlier lock, the check still open. */
    public JpathAnswerResult(boolean correct, String correctValue, String explanation, JpathCompareView compare,
                             JpathReportTargetView report) {
        this(correct, correctValue, explanation, compare, report, null, false);
    }
}
