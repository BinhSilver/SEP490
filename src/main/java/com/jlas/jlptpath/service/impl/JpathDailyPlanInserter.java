package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
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
import com.jlas.jlptpath.util.JpathPacingEngine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Inserts today's plan in its own transaction (plan decision 32) and holds the day rules: the kind of a
 * day with the skill-day make-up (#14) and the target/cap table of each day kind.
 */
@Component
public class JpathDailyPlanInserter {

    private static final Logger log = LoggerFactory.getLogger(JpathDailyPlanInserter.class);

    /** Review cap of the kana tab on every day (policy #13). */
    static final int KANA_REVIEW_CAP = 20;
    /** Kana rows of a kana-phase study day (policy #26). */
    static final int KANA_ROWS_PER_DAY = JpathPacingEngine.KANA_ROWS_PER_DAY;
    /** Learned items a tab needs for the skill-day quiz (policy #25). */
    static final int SKILL_QUIZ_MIN_ITEMS = 10;
    /** Passed kana rows that allow the KANA_ROW step on a skill day (policy #25). */
    static final int SKILL_KANA_MIN_ROWS = 2;
    static final String OPEN = "OPEN";
    static final String ABANDONED = "ABANDONED";

    /** Kind of a day: the calendar kind, a study day turned into a make-up skill day, and the kana phase. */
    record DayPlan(JpathDayKind kind, boolean skillMakeup, boolean kanaPhase) {
    }

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

    public JpathDailyPlanInserter(JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions,
            JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows, JpathPathService pathService,
            JpathProgressService progress, JpathCalendarService calendar, JpathContentReader content,
            JpathClock clock) {
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
     * Inserts the plan of {@code day} with its tabs unless it exists. Takes the path lock first (archived or missing
     * path → JPATH_NO_ACTIVE_PATH), then re-evaluates the stage (#15) and the pacing (#30) — both lock path → level
     * inside this transaction — and only then reads the level row with its lock. Abandons the open TODAY sessions of
     * older plans. A parallel insert surfaces as {@code DataIntegrityViolationException} at flush (caller ignores it).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertIfAbsent(Long userId, LocalDate day) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        if (plans.findByUserIdAndStudyDay(userId, day).isPresent()) {
            return;
        }
        progress.reevaluateStage(userId);
        pathService.refreshPacing(userId);
        JpathUserLevel level = levels.findForUpdate(path.getId(), path.getOpenLevel()).orElseThrow(() -> {
            log.error("Active JLPT path {} of user {} has no row for its open level {}", path.getId(), userId,
                    path.getOpenLevel());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
        DayPlan dayPlan = dayPlan(path, level, day);
        JpathDailyPlan plan = plans.save(JpathDailyPlan.builder().userId(userId).pathId(path.getId()).studyDay(day)
                .level(level.getLevel()).dayKind(dayPlan.kind()).kanaPhase(dayPlan.kanaPhase())
                .skillMakeup(dayPlan.skillMakeup()).build());
        LocalDateTime now = clock.now();
        List<JpathDailyTab> tabs = tabRows(dayPlan.kind(), dayPlan.kanaPhase(), path.isKanaTab(),
                path.getStudyMode(), level, path.getDailyMinutes());
        for (JpathDailyTab tab : tabs) {
            tab.setPlanId(plan.getId());
            markZeroTargetDone(tab, now);
            if (tab.getTab() == JpathTab.SKILL && nothingToQuiz(userId, level.getLevel(), dayPlan.kanaPhase())) {
                tab.setCheckPassedAt(now);
            }
        }
        dailyTabs.saveAll(tabs);
        for (JpathStudySession s : sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, OPEN,
                JpathSessionMode.TODAY.name())) {
            if (!plan.getId().equals(s.getPlanId())) {
                s.setStatus(ABANDONED);
            }
        }
        plans.flush();
    }

    /**
     * Kind of {@code day} for the path: the calendar kind (SKILL_PRACTICE with no new item left → SKILL), and a STUDY
     * day with skill days owed this week becomes a make-up SKILL day (#14). Kana phase = kana tab and kana not done.
     */
    DayPlan dayPlan(JpathUserPath path, JpathUserLevel level, LocalDate day) {
        JpathStage stage = level.getStage();
        boolean kanaPhase = path.isKanaTab() && path.getKanaCompletedAt() == null;
        boolean noNewLeft = stage == JpathStage.SKILL_PRACTICE
                && JpathPathRules.noNewItemsLeft(content, path.getUserId(), level.getLevel(), level.isPriorityMode());
        JpathDayKind kind = calendar.dayKind(path, stage, day, noNewLeft);
        if (kind == JpathDayKind.STUDY && owedSkillDays(path, stage, day) > 0) {
            return new DayPlan(JpathDayKind.SKILL, true, kanaPhase);
        }
        return new DayPlan(kind, false, kanaPhase);
    }

    /**
     * Policy #14: skill days of the calendar this week (Monday→Sunday) before {@code day}, counted from the day the
     * path was created, minus this week's SKILL plans before {@code day} that are done; never negative.
     */
    int owedSkillDays(JpathUserPath path, JpathStage stage, LocalDate day) {
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = monday;
        if (path.getCreatedAt() != null && path.getCreatedAt().toLocalDate().isAfter(from)) {
            from = path.getCreatedAt().toLocalDate();
        }
        int scheduled = 0;
        for (LocalDate d = from; d.isBefore(day); d = d.plusDays(1)) {
            if (calendar.dayKind(path, stage, d) == JpathDayKind.SKILL) {
                scheduled++;
            }
        }
        if (scheduled == 0) {
            return 0;
        }
        long done = day.equals(monday) ? 0 : plans.findByUserIdAndStudyDayBetween(path.getUserId(), monday,
                        day.minusDays(1)).stream()
                .filter(p -> p.getDayKind() == JpathDayKind.SKILL && p.getAllDoneAt() != null)
                .count();
        return (int) Math.max(0, scheduled - done);
    }

    /**
     * Tab rows of a day kind (V/K/G = the open level's sliders, m = daily minutes), plan id and timestamps unset:
     * STUDY V/K/G caps 2×; kana phase VOCAB = kanaPhaseVocab(m), K/G 0 when COMBINED, KANA 2 rows cap 20; SKILL all 0
     * plus the SKILL tab (cap 0); REVIEW_ONLY 0 with caps 4×; REST 0 with caps 2×. KANA rows (cap 20) on non-study
     * days only for paths with the kana tab.
     */
    static List<JpathDailyTab> tabRows(JpathDayKind kind, boolean kanaPhase, boolean kanaTab, JpathStudyMode mode,
            JpathUserLevel level, int minutes) {
        int v = level.getSliderVocab();
        int k = level.getSliderKanji();
        int g = level.getSliderGrammar();
        List<JpathDailyTab> out = new ArrayList<>(5);
        switch (kind) {
            case STUDY -> {
                if (kanaPhase) {
                    boolean split = mode == JpathStudyMode.SPLIT;
                    out.add(tab(JpathTab.VOCAB, JpathPacingEngine.kanaPhaseVocab(minutes), 2 * v));
                    out.add(tab(JpathTab.KANJI, split ? k : 0, 2 * k));
                    out.add(tab(JpathTab.GRAMMAR, split ? g : 0, 2 * g));
                    out.add(tab(JpathTab.KANA, KANA_ROWS_PER_DAY, KANA_REVIEW_CAP));
                } else {
                    out.add(tab(JpathTab.VOCAB, v, 2 * v));
                    out.add(tab(JpathTab.KANJI, k, 2 * k));
                    out.add(tab(JpathTab.GRAMMAR, g, 2 * g));
                }
            }
            case SKILL -> {
                addZero(out, 2, v, k, g, kanaTab);
                out.add(tab(JpathTab.SKILL, 0, 0));
            }
            case REVIEW_ONLY -> addZero(out, 4, v, k, g, kanaTab);
            case REST -> addZero(out, 2, v, k, g, kanaTab);
        }
        return out;
    }

    /** A tab with target 0 (other than SKILL) is done from the start. */
    static void markZeroTargetDone(JpathDailyTab tab, LocalDateTime now) {
        if (tab.getTab() == JpathTab.SKILL) {
            return;
        }
        if (tab.getNewTarget() <= 0) {
            if (tab.getDoneAt() == null) {
                tab.setDoneAt(now);
            }
        } else {
            tab.setDoneAt(null);
        }
    }

    /**
     * Policy #25: no V/K/G tab of the level has {@link #SKILL_QUIZ_MIN_ITEMS} counted items and the kana phase has
     * fewer than {@link #SKILL_KANA_MIN_ROWS} passed rows → the skill day has no quiz.
     */
    private boolean nothingToQuiz(Long userId, String level, boolean kanaPhase) {
        for (JpathTab tab : JpathPathRules.SLIDER_TABS) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            if (userItems.countByUserIdAndLevelAndItemTypeAndCountedAtIsNotNull(userId, level, type)
                    >= SKILL_QUIZ_MIN_ITEMS) {
                return false;
            }
        }
        return !(kanaPhase && kanaRows.countCompletedRows(userId) >= SKILL_KANA_MIN_ROWS);
    }

    private static void addZero(List<JpathDailyTab> out, int capFactor, int v, int k, int g, boolean kanaTab) {
        out.add(tab(JpathTab.VOCAB, 0, capFactor * v));
        out.add(tab(JpathTab.KANJI, 0, capFactor * k));
        out.add(tab(JpathTab.GRAMMAR, 0, capFactor * g));
        if (kanaTab) {
            out.add(tab(JpathTab.KANA, 0, KANA_REVIEW_CAP));
        }
    }

    private static JpathDailyTab tab(JpathTab tab, int target, int cap) {
        return JpathDailyTab.builder().tab(tab).newTarget(target).reviewCap(cap).build();
    }
}
