package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathDaySummaryView;
import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathPathLocks;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Daily plan of the JLPT path (plan decision 32) and the port that re-applies changed sliders to today.
 * Lock order of the module: path → level → plan → tabs. The plan is created only by {@link #ensurePlanExists}, outside
 * any writer transaction; writers lock it with {@link #lockToday} after their own path lock.
 */
@Service
public class JpathDailyPlanServiceImpl implements JpathDailyPlanService, JpathTodayTargetsPort {

    private static final Logger log = LoggerFactory.getLogger(JpathDailyPlanServiceImpl.class);
    /** Unique key of jpath_daily_plan (user_id, study_day): the only integrity error a parallel insert may cause. */
    static final String PLAN_DAY_KEY = "uk_jpath_plan_user_day";

    private final JpathPathService pathService;
    private final JpathDailyPlanInserter inserter;
    private final JpathTodayViewAssembler views;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathUserLevelRepository levels;
    private final JpathClock clock;
    private final JpathDaySummaryReader summaryReader;

    public JpathDailyPlanServiceImpl(JpathPathService pathService, JpathDailyPlanInserter inserter,
            JpathTodayViewAssembler views, JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs,
            JpathUserLevelRepository levels, JpathClock clock,
            JpathDaySummaryReader summaryReader) {
        this.pathService = pathService;
        this.inserter = inserter;
        this.views = views;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.levels = levels;
        this.clock = clock;
        this.summaryReader = summaryReader;
    }

    // ---- plan --------------------------------------------------------------------------------------------------

    @Override
    public void ensurePlanExists(Long userId) {
        if (JpathPathLocks.held()) {
            throw new IllegalStateException("ensurePlanExists called while the transaction holds the path lock");
        }
        if (views.currentPlan(userId).isPresent()) {
            return;
        }
        LocalDate today = clock.today(userId);
        try {
            inserter.insertIfAbsent(userId, today);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicatePlan(e)) {
                throw e;
            }
            if (views.currentPlan(userId).isEmpty()) {
                log.error("Duplicate daily plan of user {} for {} reported but no plan is visible", userId, today);
                throw new ApiException(ErrorCode.INTERNAL_ERROR);
            }
        }
    }

    @Override
    public JpathDailyPlan ensureToday(Long userId) {
        ensurePlanExists(userId);
        LocalDate today = clock.today(userId);
        return views.currentPlan(userId).orElseThrow(() -> JpathDailyPlanInserter.missingPlan(userId, today));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public JpathDailyPlan lockToday(JpathUserPath lockedPath) {
        if (!JpathPathLocks.held()) {
            throw new IllegalStateException("lockToday needs the path lock of the caller's transaction");
        }
        Long userId = lockedPath.getUserId();
        levels.findForUpdate(lockedPath.getId(), lockedPath.getOpenLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        LocalDate today = clock.today(userId);
        return plans.findForUpdate(userId, today).orElseThrow(() -> JpathDailyPlanInserter.missingPlan(userId, today));
    }

    @Override
    public JpathTodayView today(Long userId) {
        ensurePlanExists(userId);
        return views.view(userId);
    }

    /** Not transactional: the plan is created first, then read in a fresh transaction (never a stale snapshot). */
    @Override
    public JpathDaySummaryView summary(Long userId) {
        pathService.requireActive(userId);
        JpathDailyPlan plan = ensureToday(userId);
        return summaryReader.read(userId, plan);
    }

    @Override
    public JpathTodayView chooseReviewDay(Long userId) {
        ensurePlanExists(userId);
        inserter.switchToReviewDay(userId);
        return views.view(userId);
    }

    /**
     * Port (#29), under the caller's path → level locks. Tabs with no new item done and no open TODAY session on the
     * tab or COMBINED take the targets and caps of the table again; a plan made for another level (the level was
     * lowered) moves to the open level first. Never creates the plan (preflight C10): none today → empty.
     */
    @Override
    @Transactional
    public Set<JpathTab> refreshTodayTargets(Long userId) {
        Optional<JpathUserPath> active = pathService.findActive(userId);
        if (active.isEmpty()) {
            return EnumSet.noneOf(JpathTab.class);
        }
        JpathUserPath path = active.get();
        JpathUserLevel level = levels.findForUpdate(path.getId(), path.getOpenLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        Optional<JpathDailyPlan> found = plans.findForUpdate(userId, clock.today(userId));
        if (found.isEmpty()) {
            return EnumSet.noneOf(JpathTab.class);
        }
        JpathDailyPlan plan = found.get();
        if (!path.getOpenLevel().equals(plan.getLevel())) {
            plan.setLevel(path.getOpenLevel());
        }
        Set<String> openTabs = new LinkedHashSet<>();
        views.openTodaySessions(userId, plan).forEach(s -> openTabs.add(s.getTab()));
        List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
        Set<JpathTab> kept = EnumSet.noneOf(JpathTab.class);
        for (JpathDailyTab t : tabs) {
            if (t.getTab() != JpathTab.SKILL && (t.getNewDone() > 0 || openTabs.contains(t.getTab().name())
                    || openTabs.contains("COMBINED"))) {
                kept.add(t.getTab());
            }
        }
        JpathDayRules.applyRows(plan, tabs, JpathDayRules.tabRows(plan.getDayKind(), plan.isKanaPhase(),
                path.isKanaTab(), path.getStudyMode(), level, path.getDailyMinutes()), kept, clock.now(), dailyTabs);
        dailyTabs.flush();
        return kept;
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    @Override
    public int effectiveTarget(JpathDailyPlan plan, JpathDailyTab tab) {
        return views.effectiveTarget(plan, tab);
    }

    @Override
    public Set<JpathTab> requiredTabs(JpathDailyPlan plan) {
        return JpathTodayViewAssembler.requiredTabs(plan);
    }

    @Override
    public List<JpathItemRef> plannedNewItems(Long userId, JpathTab tab) {
        return views.plannedNewItems(userId, tab);
    }

    @Override
    public boolean skillDayMissed(Long userId) {
        return views.skillDayMissed(userId);
    }

    /** True when the integrity error names the (user_id, study_day) key, i.e. a parallel request inserted first. */
    static boolean isDuplicatePlan(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(PLAN_DAY_KEY)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
