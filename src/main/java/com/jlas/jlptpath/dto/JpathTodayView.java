package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] The "Hôm nay" block: today's plan with its tabs, the combined-day steps, the estimate, up to 5 hero
 * cards (planLevel = level today's plan was made for; openLevel = level open now, they differ after a level-up
 * the same day — owner 27/11), the review-day hint (#13), the missed skill day warning (#14) and the session to resume (null when none).
 */
public record JpathTodayView(LocalDate day, JpathDayKind kind, boolean kanaPhase, boolean skillMakeup,
                             List<JpathTodayTab> tabs, List<JpathCombinedStep> combinedSteps, int estimatedMinutes,
                             List<JpathHeroCard> heroCards, boolean allDone, boolean reviewDayHint,
                             boolean skillDayMissed, JpathResumeView resume, String planLevel, String openLevel) {

    /** Callers that do not know the levels (tests, old code). */
    public JpathTodayView(LocalDate day, JpathDayKind kind, boolean kanaPhase, boolean skillMakeup,
                          List<JpathTodayTab> tabs, List<JpathCombinedStep> combinedSteps, int estimatedMinutes,
                          List<JpathHeroCard> heroCards, boolean allDone, boolean reviewDayHint,
                          boolean skillDayMissed, JpathResumeView resume) {
        this(day, kind, kanaPhase, skillMakeup, tabs, combinedSteps, estimatedMinutes, heroCards, allDone, reviewDayHint,
                skillDayMissed, resume, null, null);
    }
}
