package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathReadiness;
import com.jlas.jlptpath.enums.JpathSkill;

/** [JLAS-W0108] Plan 3 Task 15 — timed section practice (#40) and exam readiness (#41). */
public interface JpathSectionPracticeService {

    /**
     * Starts (or resumes) today's section practice of {@code skill} at the open level: one set per available type,
     * as a timed SKILL_SECTION check. Before the SKILL_PRACTICE stage → JPATH_SECTION_LOCKED; nothing to ask →
     * JPATH_SKILL_NO_SETS. An open section whose check is still running is returned as it is.
     */
    JpathCheckView start(Long userId, JpathSkill skill);

    /** #41 for {@code level} (null = the open level): every type done once and the last two sections of each skill passed. */
    JpathReadiness readiness(Long userId, String level);
}
