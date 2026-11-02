package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathDaySummaryView;
import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Set;

/**
 * [JLAS-W0108] The learner's daily plan (plan decision 32): today's kind, per-tab new-item targets and review caps.
 */
public interface JpathDailyPlanService {

    /**
     * Creates today's plan when it is missing, in the insert's own transaction (REQUIRES_NEW: stage re-evaluation,
     * pacing, plan + tabs, abandon older open TODAY sessions). Not transactional itself: call it before opening a
     * writer transaction (controller or thin facade). Called while the current transaction holds the path lock →
     * IllegalStateException. No ACTIVE path → JPATH_NO_ACTIVE_PATH. A parallel insert of the same day is ignored.
     */
    void ensurePlanExists(Long userId);

    /**
     * {@link #ensurePlanExists} then today's plan read in a fresh read-only transaction. The returned entity is a
     * read-only snapshot (never write through it); writers use {@link #lockToday}.
     */
    JpathDailyPlan ensureToday(Long userId);

    /**
     * Today's plan locked for writing, inside the caller's transaction (MANDATORY) which already holds the path lock
     * ({@code findActiveForUpdate} → {@code lockedPath}; IllegalStateException otherwise). Takes the level lock, then
     * the plan lock (lock order path → level → plan → tabs; lock tabs afterwards with
     * {@code JpathDailyTabRepository.findForUpdateByPlanId}). No plan today → INTERNAL_ERROR: run
     * {@link #ensurePlanExists} before the transaction.
     */
    JpathDailyPlan lockToday(JpathUserPath lockedPath);

    /** The "Hôm nay" block: {@link #ensurePlanExists} then the view read in a fresh read-only transaction. */
    JpathTodayView today(Long userId);

    /**
     * Turns today into a review day (REVIEW_ONLY, caps 4× the sliders): only on a STUDY day before any new item was
     * done (JPATH_REVIEW_DAY_NOT_ALLOWED otherwise). Like {@link #today}: not called inside a writer transaction.
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

    /**
     * Summary of today ("Xong hôm nay"; {@link #ensurePlanExists} first, so not inside a writer transaction): today's new learned / reviewed counts,
     * check accuracy, wrong items, CP paid today ({@code cp_awarded}), streak and the level % before today and now.
     */
    JpathDaySummaryView summary(Long userId);

    /** Policy #14: a skill day of this week was missed and no study day is left this week to make it up. */
    boolean skillDayMissed(Long userId);
}
