package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathProgressService;
import com.jlas.jlptpath.util.JpathPathLocks;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Writes of the daily plan: the insert of a day's plan in its own transaction (plan decision 32) and the
 * switch to a review day. Both lock path → level → plan, the module's lock order. The day rules live in
 * {@link JpathDayRules}.
 */
@Component
public class JpathDailyPlanInserter {

    private static final Logger log = LoggerFactory.getLogger(JpathDailyPlanInserter.class);

    static final String OPEN = "OPEN";
    static final String ABANDONED = "ABANDONED";

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathStudySessionRepository sessions;
    private final JpathUserItemRepository userItems;
    private final JpathKanaRowRepository kanaRows;
    private final JpathPathService pathService;
    private final JpathProgressService progress;
    private final JpathCalendarService calendar;
    private final JpathContentReader content;
    private final JpathClock clock;
    private final ObjectProvider<JpathDayCompletion> completion;
    // Plan 3: which skills have visible content at a level; field-injected so the constructor stays as it was
    @Autowired
    private ObjectProvider<JpathSkillPicker> skillPicker;

    public JpathDailyPlanInserter(JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions,
            JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows, JpathPathService pathService,
            JpathProgressService progress, JpathCalendarService calendar, JpathContentReader content,
            JpathClock clock, ObjectProvider<JpathDayCompletion> completion) {
        this.completion = completion;
        this.paths = paths;
        this.levels = levels;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.sessions = sessions;
        this.userItems = userItems;
        this.kanaRows = kanaRows;
        this.pathService = pathService;
        this.progress = progress;
        this.calendar = calendar;
        this.content = content;
        this.clock = clock;
    }

    /**
     * Inserts the plan of {@code day} with its tabs unless it exists. Must not run while the caller's transaction
     * holds the path lock ({@link JpathPathLocks}): IllegalStateException instead of waiting on its own lock. Takes
     * the path lock first (archived or missing path → JPATH_NO_ACTIVE_PATH), then re-evaluates the stage (#15) and
     * the pacing (#30) — both lock path → level inside this transaction — and only then reads the level row with its
     * lock. A SKILL day whose quiz has no source (#25) gets its SKILL check passed at once. Abandons the open TODAY
     * sessions of older plans. A parallel insert surfaces as {@code DataIntegrityViolationException} on
     * uk_jpath_plan_user_day at flush.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertIfAbsent(Long userId, LocalDate day) {
        if (JpathPathLocks.held()) {
            throw new IllegalStateException("Daily plan insert called while the transaction holds the path lock");
        }
        try {
            insert(userId, day);
        } finally {
            JpathPathLocks.release();
        }
    }

    /**
     * Makes today's STUDY plan a review day (REVIEW_ONLY, caps 4× the sliders read under the level lock) with path →
     * level → plan locks; a day that is not STUDY or has a new item done → JPATH_REVIEW_DAY_NOT_ALLOWED; no plan →
     * INTERNAL_ERROR (the caller runs {@code ensurePlanExists} first).
     */
    @Transactional
    public void switchToReviewDay(Long userId) {
        boolean heldBefore = JpathPathLocks.held();
        try {
            JpathUserPath path = paths.findActiveForUpdate(userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
            JpathUserLevel level = lockLevel(path);
            LocalDate today = clock.today(userId);
            JpathDailyPlan plan = plans.findForUpdate(userId, today)
                    .orElseThrow(() -> missingPlan(plans, userId, today));
            List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
            if (plan.getDayKind() != JpathDayKind.STUDY || tabs.stream().anyMatch(t -> t.getNewDone() > 0)) {
                throw new ApiException(ErrorCode.JPATH_REVIEW_DAY_NOT_ALLOWED);
            }
            plan.setDayKind(JpathDayKind.REVIEW_ONLY);
            JpathDayRules.applyRows(plan, tabs, JpathDayRules.tabRows(JpathDayKind.REVIEW_ONLY, plan.isKanaPhase(),
                            path.isKanaTab(), path.getStudyMode(), level, path.getDailyMinutes()),
                    EnumSet.noneOf(JpathTab.class), clock.now(), dailyTabs);
            dailyTabs.flush();
            JpathDayCompletion days = completion.getIfAvailable();
            if (days != null) {
                // A review day with no card due is done at once (no CP); rows added by applyRows are read again.
                days.settleWithoutReward(userId, plan, dailyTabs.findForUpdateByPlanId(plan.getId()));
            }
        } finally {
            if (!heldBefore) {
                JpathPathLocks.release();
            }
        }
    }

    private void insert(Long userId, LocalDate day) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        if (plans.findByUserIdAndStudyDay(userId, day).isPresent()) {
            return;
        }
        progress.reevaluateStage(userId);
        pathService.refreshPacing(userId);
        JpathUserLevel level = lockLevel(path);
        JpathDayRules.DayPlan dayPlan = JpathDayRules.dayPlan(content, calendar, plans, path, level, day);
        JpathDailyPlan plan = plans.save(JpathDailyPlan.builder().userId(userId).pathId(path.getId()).studyDay(day)
                .level(level.getLevel()).dayKind(dayPlan.kind()).kanaPhase(dayPlan.kanaPhase())
                .skillMakeup(dayPlan.skillMakeup()).build());
        LocalDateTime now = clock.now();
        List<JpathDailyTab> tabs = JpathDayRules.tabRows(dayPlan.kind(), dayPlan.kanaPhase(), path.isKanaTab(),
                path.getStudyMode(), level, path.getDailyMinutes());
        for (JpathDailyTab tab : tabs) {
            tab.setPlanId(plan.getId());
            JpathDayRules.markZeroTargetDone(tab, now);
            if (tab.getTab() == JpathTab.SKILL) {
                int skills = skillsWithContent(level.getLevel());
                tab.setNewTarget(skills);
                if (skills == 0) {
                    tab.setDoneAt(now); // no visible skill content: the skill day is done by its reviews
                }
            }
        }
        List<JpathDailyTab> saved = dailyTabs.saveAll(tabs);
        JpathDayCompletion days = completion.getIfAvailable();
        if (days != null) {
            days.settleWithoutReward(userId, plan, saved);
        }
        for (JpathStudySession s : sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, OPEN,
                JpathSessionMode.TODAY.name())) {
            if (!plan.getId().equals(s.getPlanId())) {
                s.setStatus(ABANDONED);
            }
        }
        plans.flush();
    }

    private JpathUserLevel lockLevel(JpathUserPath path) {
        return levels.findForUpdate(path.getId(), path.getOpenLevel()).orElseThrow(() -> {
            log.error("Active JLPT path {} of user {} has no row for its open level {}", path.getId(),
                    path.getUserId(), path.getOpenLevel());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
    }

    static ApiException missingPlan(Long userId, LocalDate day) {
        log.error("No daily plan of user {} for {} under lock; ensurePlanExists must run first", userId, day);
        return new ApiException(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * Today's plan is missing under lock. When yesterday's plan exists, the learner's day rolled over between
     * {@code ensurePlanExists} and the lock: CONFLICT (the page reloads). Otherwise a bug: logged INTERNAL_ERROR.
     */
    static ApiException missingPlan(JpathDailyPlanRepository plans, Long userId, LocalDate day) {
        if (plans.findByUserIdAndStudyDay(userId, day.minusDays(1)).isPresent()) {
            log.info("Day of user {} rolled over to {} between ensurePlanExists and the plan lock", userId, day);
            return new ApiException(ErrorCode.CONFLICT);
        }
        return missingPlan(userId, day);
    }

    /** Plan 3 Task 16: skills (Reading, Listening) with a visible type at {@code level}; 2 without the skill module. */
    int skillsWithContent(String level) {
        JpathSkillPicker picker = skillPicker == null ? null : skillPicker.getIfAvailable();
        if (picker == null) {
            return JpathDayRules.SKILL_SESSIONS_PER_DAY;
        }
        JpathSkillPicker.LevelContent content = picker.content(level);
        return (content.hasAvailable(JpathSkill.READING) ? 1 : 0) + (content.hasAvailable(JpathSkill.LISTENING) ? 1 : 0);
    }
}
