package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathSkillCheckView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import java.util.Set;

/**
 * [JLAS-W0108] Plan 3 Task 13 — the skill side of a SKILL_* check, called by {@code JpathCheckServiceImpl}: what the
 * learner sees per set, and what a submit changes outside the check row.
 */
public interface JpathSkillPracticeService {

    /** Sets of the check; a set whose questions are all in {@code locked} shows its translation, script and words. */
    JpathSkillCheckView view(JpathCheckSet set, Set<String> locked);

    /**
     * After a submit (inside its transaction, the path lock held, the check row already SUBMITTED): type stats and set
     * uses for SKILL_PRACTICE, then the skill session moves on. No CP and no plan change here.
     */
    void afterSubmit(JpathCheckSet set);
}
