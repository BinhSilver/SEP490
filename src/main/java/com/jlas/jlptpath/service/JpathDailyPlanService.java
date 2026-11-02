package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Set;

/**
 * [JLAS-W0108] The learner's daily plan (plan decision 32): today's kind, per-tab new-item targets and review caps.
 */
public interface JpathDailyPlanService {

    /**
     * Today's plan, created when missing, returned locked ({@code PESSIMISTIC_WRITE}) in the caller's transaction.
     * Order: plain read; when missing the plan is inserted in its own transaction (re-evaluates the stage and the
     * pacing first); then the row is locked. Callers must not hold any jpath lock before calling it (the insert
     * transaction takes the path and level locks). No ACTIVE path → JPATH_NO_ACTIVE_PATH.
     */
    JpathDailyPlan ensureToday(Long userId);

    /** The "Hôm nay" block (creates today's plan when missing). */
    JpathTodayView today(Long userId);

    /**
     * Turns today into a review day (REVIEW_ONLY, caps 4× the sliders): only on a STUDY day before any new item was
     * done (JPATH_REVIEW_DAY_NOT_ALLOWED otherwise).
     */
    JpathTodayView chooseReviewDay(Long userId);

    /** min(new target, new done + learnable items not started yet); kana-phase vocabulary counts kana-only words. */
    int effectiveTarget(JpathDailyPlan plan, JpathDailyTab tab);

    /** Tabs that must be done for the day: STUDY {V,K,G}; kana phase {KANA, VOCAB}; SKILL {SKILL}; otherwise none. */
    Set<JpathTab> requiredTabs(JpathDailyPlan plan);

    /**
     * New items of today's TODAY round for {@code tab}: those of the open session when there is one, otherwise the
     * next new codes up to the remaining target. No side effect (never creates the plan or a session).
     */
    List<JpathItemRef> plannedNewItems(Long userId, JpathTab tab);

    /** Policy #14: a skill day of this week was missed and no study day is left this week to make it up. */
    boolean skillDayMissed(Long userId);
}
