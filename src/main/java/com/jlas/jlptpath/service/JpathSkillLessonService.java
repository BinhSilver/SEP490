package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathSkillLessonCheckResult;
import com.jlas.jlptpath.dto.JpathSkillLessonView;

/**
 * [JLAS-W0108] Strategy lessons of the skill types and their comprehension check (#36). A session id that is not the
 * learner's, or of another type, reads like an unknown one (JPATH_SKILL_SESSION_NOT_FOUND); a type that is not visible
 * (level not released, lesson hidden) → JPATH_SKILL_TYPE_NOT_FOUND.
 */
public interface JpathSkillLessonService {

    /**
     * The lesson. {@code passed}: the given session's check is passed; without a session id, the latest open session
     * of the type, else any of the learner's sessions of the type.
     */
    JpathSkillLessonView view(Long userId, String typeCode, Long sessionId);

    /**
     * Checks one pick (o1..oN; anything else → VALIDATION_FAILED). Correct → the given session, or every open session
     * of the learner of this type, gets {@code lesson_passed_at = now} and moves THEORY → PRACTICE, once (a later
     * correct pick changes nothing). Wrong → nothing is written.
     */
    JpathSkillLessonCheckResult check(Long userId, String typeCode, String option, Long sessionId);
}
