package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathSkillSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSkillSessionView;

/**
 * [JLAS-W0108] Skill sessions (Đọc · Nghe): DAY on a skill day, FREE any day. Another learner's session id reads like
 * an unknown one (JPATH_SKILL_SESSION_NOT_FOUND) and nothing is written.
 */
public interface JpathSkillSessionService {

    /**
     * Starts a session, or returns the open one of today's plan for (skill, kind). DAY: only on a SKILL day (make-up
     * days included; otherwise JPATH_SKILL_NOT_SKILL_DAY); the type is the type of the day (#37) whatever the client
     * sent; a DAY session of the skill already done today is returned as it is. FREE: {@code typeCode} of a visible
     * type of the skill at the open level or a lower one (otherwise JPATH_SKILL_TYPE_NOT_FOUND). No visible set →
     * JPATH_SKILL_NO_SETS. Sets: #34 floor, budget daily minutes / 2, #39 order. Creates today's plan first, so it is
     * not called inside a writer transaction.
     */
    JpathSkillSessionView start(Long userId, JpathSkillSessionStartRequest request);

    JpathSkillSessionView get(Long userId, Long sessionId);

    /**
     * F238: skill sessions carried from earlier weeks (no active path → nothing owed). A DAY session may start on a
     * normal study day while {@code offerToday}; it pays one carried session and does not touch the day's tabs.
     */
    default com.jlas.jlptpath.dto.JpathSkillCarryView carry(Long userId) {
        return new com.jlas.jlptpath.dto.JpathSkillCarryView(0, "READING", false, false);
    }

    /**
     * Moves the session one step when it may: THEORY → PRACTICE once the lesson check is passed (otherwise
     * JPATH_SKILL_LESSON_REQUIRED); PRACTICE → DICTATION (listening) or DONE (reading) once the practice check is
     * SUBMITTED; DICTATION → DONE once the dictation check is SUBMITTED. Otherwise the session stays as it is. DONE sets
     * {@code completed_at} and the practice score. Joins the caller's transaction when there is one.
     */
    JpathSkillSessionView advance(Long userId, Long sessionId);
}
