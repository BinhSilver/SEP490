package com.jlas.jlptpath.enums;

/** [JLAS-W0108] Kind of an issued check set (jpath_check_set.kind). */
public enum JpathCheckKind {
    MICRO_CHECK, GROUP_EXERCISE, SKIP_ONE, TOPIC_KNOWN, FREE_QUIZ, CONFUSABLE, KANA_ROW, GRAMMAR_PRACTICE,
    GRAMMAR_EXTRA, GRAMMAR_REVIEW,
    /** Plan 3 skill tools: strategy lesson check, practice sets, dictation, timed section practice. */
    SKILL_LESSON, SKILL_PRACTICE, SKILL_DICTATION, SKILL_SECTION
}
