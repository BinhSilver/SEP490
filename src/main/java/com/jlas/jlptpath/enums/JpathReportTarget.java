package com.jlas.jlptpath.enums;

/** [JLAS-W0108] Target of a content error report (jpath_content_report.target_type). */
public enum JpathReportTarget {
    VOCAB, KANJI, GRAMMAR, KANA, EXAMPLE, QUESTION,
    /** Plan 3: a reading/listening practice set and one of its questions. */
    SKILL_SET, SKILL_QUESTION
}
