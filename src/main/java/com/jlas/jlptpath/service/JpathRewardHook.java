package com.jlas.jlptpath.service;

import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;

/**
 * [JLAS-W0108] Reward side of the JLPT path (CP, streak, badges), implemented outside this module. Called
 * synchronously inside the caller's transaction, only for today's plan (late completions record the marks without
 * calling it); no bean → nothing is called. Amounts and ref ids: {@link com.jlas.jlptpath.util.JpathRewardPolicy}.
 */
public interface JpathRewardHook {

    /** A V/K/G/KANA tab with a target &gt; 0 finished for {@code day}; {@code rewardEligible} false → no CP. */
    void onTabDayCompleted(Long userId, LocalDate day, JpathTab tab, boolean rewardEligible);

    /** Every due review of {@code day} was done (at least one review). */
    void onReviewsCleared(Long userId, LocalDate day);

    /** The whole day is done; {@code rewardEligible} false (a tab of the day was too fast) → no CP. */
    void onDayCompleted(Long userId, LocalDate day, JpathDayKind kind, boolean rewardEligible);

    /** Progress changed (badges, stats); called once per evaluation of today's plan. */
    void onProgressChanged(Long userId);

    /** Plan 3 #44: a DAY skill session of {@code skill} done on {@code day} (the learner's today); +2 once a day. */
    default void onSkillSessionDone(Long userId, LocalDate day, JpathSkill skill) {
    }
}
