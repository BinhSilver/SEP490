package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathLevelProgressView;
import com.jlas.jlptpath.dto.JpathTabProgressView;
import com.jlas.jlptpath.enums.JpathTab;

/** [JLAS-W0108] Progress of tabs and levels, and the stage changes it triggers (policy #15, #16). */
public interface JpathProgressService {

    /** Progress of {@code level} (N1..N5) for the learner. */
    JpathLevelProgressView level(Long userId, String level);

    /** Progress of one tab; KANA ignores {@code level}; SKILL gives IllegalArgumentException. */
    JpathTabProgressView tab(Long userId, String level, JpathTab tab);

    /**
     * Policy #15 on the ACTIVE path (none: nothing): the open level in STUDY moves to SKILL_PRACTICE (with
     * skill_practice_at = now) once VOCAB, KANJI and GRAMMAR each have {@code counted * 100 >= 80 * total} and
     * total &gt; 0; a path with the kana tab gets kana_completed_at once all 36 rows are done. Other stages (also
     * PASSED after lowering the level) stay. Takes the path lock, then the level lock, so it must be the first code
     * to load the path and its level in the caller's transaction (a cached entity is not refreshed).
     *
     * @return true when the stage or kana_completed_at changed; the caller then refreshes the pacing
     */
    boolean reevaluateStage(Long userId);
}
