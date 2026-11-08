package com.jlas.jlptpath.service;

import com.jlas.jlptpath.entity.JpathSkillSession;

/**
 * [JLAS-W0108] Port through which a skill session gets its checks (jpath_check_set kinds SKILL_PRACTICE and
 * SKILL_DICTATION). Implemented by the skill check builder (practice questions, dictation); until a bean exists the
 * session keeps null tokens and stays at PRACTICE. Both calls run inside the caller's writer transaction, which
 * already holds the path lock; the session has its id and set codes.
 */
public interface JpathSkillCheckIssuer {

    /** Issues the practice check of the session's sets and returns its token. */
    String issuePractice(JpathSkillSession session);

    /** Issues the dictation check of a listening session and returns its token; null when it has no line. */
    String issueDictation(JpathSkillSession session);
}
