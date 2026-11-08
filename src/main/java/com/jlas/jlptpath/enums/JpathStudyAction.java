package com.jlas.jlptpath.enums;

/**
 * [JLAS-W0108] Action of one study event (jpath_study_event.action). FIRST_RATE / SKIP_KNOWN on a NEW card,
 * REVIEW_RATE / MARK_KNOWN on a REVIEW card, RETYPE / MARK_KNOWN on a RETYPE card; CHECK_ANSWER is written by the
 * check service only.
 */
public enum JpathStudyAction {
    FIRST_RATE, REVIEW_RATE, RETYPE, SKIP_KNOWN, MARK_KNOWN, CHECK_ANSWER
}
