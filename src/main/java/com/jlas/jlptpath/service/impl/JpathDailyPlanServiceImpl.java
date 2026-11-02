package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCombinedStep;
import com.jlas.jlptpath.dto.JpathHeroCard;
import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathResumeView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathTodayTab;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathQueueStep;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Daily plan of the JLPT path (plan decision 32) and the port that re-applies changed sliders to today.
 * Lock order of the module: path → level → plan. {@link #ensureToday} takes only the plan lock (the insert runs in
 * its own transaction before it); {@link #refreshTodayTargets} runs under the caller's path → level locks and then
 * locks the plan.
 */
@Service
public class JpathDailyPlanServiceImpl implements JpathDailyPlanService, JpathTodayTargetsPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HERO_CARDS = 5;
    private static final String COMBINED = JpathSessionTab.COMBINED.name();
    private static final String TODAY = JpathSessionMode.TODAY.name();

    private final JpathPathService pathService;
    private final JpathDailyPlanInserter inserter;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathStudySessionRepository sessions;
    private final JpathUserItemRepository userItems;
    private final JpathKanaRowRepository kanaRows;
    private final JpathUserLevelRepository levels;
    private final JpathCalendarService calendar;
    private final JpathContentReader content;
    private final JpathClock clock;

    public JpathDailyPlanServiceImpl(JpathPathService pathService, JpathDailyPlanInserter inserter,
            JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions,
            JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows, JpathUserLevelRepository levels,
            JpathCalendarService calendar, JpathContentReader content, JpathClock clock) {
        this.pathService = pathService;
        this.inserter = inserter;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.sessions = sessions;
        this.userItems = userItems;
        this.kanaRows = kanaRows;
        this.levels = levels;
        this.calendar = calendar;
        this.content = content;
        this.clock = clock;
    }

    // ---- plan --------------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathDailyPlan ensureToday(Long userId) {
        pathService.requireActive(userId);
        LocalDate today = clock.today(userId);
        if (plans.findByUserIdAndStudyDay(userId, today).isEmpty()) {
            try {
                inserter.insertIfAbsent(userId, today);
            } catch (DataIntegrityViolationException e) {
                // A parallel request inserted today's plan first; lock that one below.
            }
        }
        return plans.findForUpdate(userId, today)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    @Override
    @Transactional
    public JpathTodayView today(Long userId) {
        JpathDailyPlan plan = ensureToday(userId);
        return view(userId, plan);
    }

    @Override
    @Transactional
    public JpathTodayView chooseReviewDay(Long userId) {
        JpathDailyPlan plan = ensureToday(userId);
        List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
        if (plan.getDayKind() != JpathDayKind.STUDY || tabs.stream().anyMatch(t -> t.getNewDone() > 0)) {
            throw new ApiException(ErrorCode.JPATH_REVIEW_DAY_NOT_ALLOWED);
        }
        JpathUserPath path = pathService.requireActive(userId);
        JpathUserLevel level = pathService.openLevelState(userId);
        plan.setDayKind(JpathDayKind.REVIEW_ONLY);
        apply(plan, tabs, JpathDailyPlanInserter.tabRows(JpathDayKind.REVIEW_ONLY, plan.isKanaPhase(),
                path.isKanaTab(), path.getStudyMode(), level, path.getDailyMinutes()), EnumSet.noneOf(JpathTab.class));
        dailyTabs.flush();
        return view(userId, plan);
    }

    /**
     * Port (#29). Tabs with no new item done and no open TODAY session on the tab or COMBINED take the targets and
     * caps of the table again; a plan made for another level (the level was lowered) moves to the open level first.
     * Never creates the plan (preflight C10): none today → empty.
     */
    @Override
    @Transactional
    public Set<JpathTab> refreshTodayTargets(Long userId) {
        Optional<JpathUserPath> active = pathService.findActive(userId);
        if (active.isEmpty()) {
            return EnumSet.noneOf(JpathTab.class);
        }
        Optional<JpathDailyPlan> found = plans.findForUpdate(userId, clock.today(userId));
        if (found.isEmpty()) {
            return EnumSet.noneOf(JpathTab.class);
        }
        JpathDailyPlan plan = found.get();
        JpathUserPath path = active.get();
        JpathUserLevel level = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        if (!path.getOpenLevel().equals(plan.getLevel())) {
            plan.setLevel(path.getOpenLevel());
        }
        Set<String> openTabs = new LinkedHashSet<>();
        openTodaySessions(userId, plan).forEach(s -> openTabs.add(s.getTab()));
        List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
        Set<JpathTab> kept = EnumSet.noneOf(JpathTab.class);
        for (JpathDailyTab t : tabs) {
            if (t.getTab() != JpathTab.SKILL && (t.getNewDone() > 0 || openTabs.contains(t.getTab().name())
                    || openTabs.contains(COMBINED))) {
                kept.add(t.getTab());
            }
        }
        apply(plan, tabs, JpathDailyPlanInserter.tabRows(plan.getDayKind(), plan.isKanaPhase(), path.isKanaTab(),
                path.getStudyMode(), level, path.getDailyMinutes()), kept);
        dailyTabs.flush();
        return kept;
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public int effectiveTarget(JpathDailyPlan plan, JpathDailyTab tab) {
        int target = tab.getNewTarget();
        if (target <= 0 || tab.getTab() == JpathTab.SKILL) {
            return 0;
        }
        int remaining = target - tab.getNewDone();
        if (remaining <= 0) {
            return target;
        }
        int available = tab.getTab() == JpathTab.KANA
                ? Math.max(0, JpathTabCountsReader.KANA_ROWS - (int) kanaRows.countCompletedRows(plan.getUserId()))
                : newCodes(plan.getUserId(), plan.getLevel(), tab.getTab(), plan.isKanaPhase(),
                        priorityMode(plan.getPathId(), plan.getLevel()), remaining).size();
        return tab.getNewDone() + Math.min(remaining, available);
    }

    @Override
    public Set<JpathTab> requiredTabs(JpathDailyPlan plan) {
        return switch (plan.getDayKind()) {
            case STUDY -> plan.isKanaPhase() ? EnumSet.of(JpathTab.KANA, JpathTab.VOCAB)
                    : EnumSet.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
            case SKILL -> EnumSet.of(JpathTab.SKILL);
            default -> EnumSet.noneOf(JpathTab.class);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathItemRef> plannedNewItems(Long userId, JpathTab tab) {
        Optional<JpathUserPath> active = pathService.findActive(userId);
        if (active.isEmpty() || tab == JpathTab.SKILL) {
            return List.of();
        }
        JpathUserPath path = active.get();
        LocalDate today = clock.today(userId);
        Optional<JpathDailyPlan> stored = plans.findByUserIdAndStudyDay(userId, today);
        String level;
        boolean kanaPhase;
        int remaining;
        if (stored.isPresent()) {
            JpathDailyPlan plan = stored.get();
            Optional<List<JpathItemRef>> fromSession = sessionNewItems(userId, plan, tab);
            if (fromSession.isPresent()) {
                return fromSession.get();
            }
            level = plan.getLevel();
            kanaPhase = plan.isKanaPhase();
            remaining = dailyTabs.findByPlanIdOrderByTab(plan.getId()).stream().filter(t -> t.getTab() == tab)
                    .findFirst().map(t -> t.getNewTarget() - t.getNewDone()).orElse(0);
        } else {
            JpathUserLevel open = pathService.openLevelState(userId);
            JpathDailyPlanInserter.DayPlan day = inserter.dayPlan(path, open, today);
            level = open.getLevel();
            kanaPhase = day.kanaPhase();
            remaining = JpathDailyPlanInserter.tabRows(day.kind(), day.kanaPhase(), path.isKanaTab(),
                            path.getStudyMode(), open, path.getDailyMinutes()).stream()
                    .filter(t -> t.getTab() == tab).findFirst().map(JpathDailyTab::getNewTarget).orElse(0);
        }
        if (remaining <= 0 || tab == JpathTab.KANA) {
            return List.of();
        }
        JpathItemType type = JpathItemType.valueOf(tab.name());
        return newCodes(userId, level, tab, kanaPhase, priorityMode(path.getId(), level), remaining).stream()
                .map(code -> new JpathItemRef(type, code)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean skillDayMissed(Long userId) {
        Optional<JpathUserPath> active = pathService.findActive(userId);
        if (active.isEmpty()) {
            return false;
        }
        JpathUserPath path = active.get();
        JpathStage stage = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(JpathUserLevel::getStage).orElse(JpathStage.STUDY);
        LocalDate today = clock.today(userId);
        int owed = inserter.owedSkillDays(path, stage, today);
        if (owed <= 0) {
            return false;
        }
        Optional<JpathDailyPlan> plan = plans.findByUserIdAndStudyDay(userId, today);
        boolean todayPays = plan.map(JpathDailyPlan::isSkillMakeup)
                .orElseGet(() -> calendar.dayKind(path, stage, today) == JpathDayKind.STUDY);
        if (todayPays) {
            owed--;
        }
        if (owed <= 0) {
            return false;
        }
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        for (LocalDate d = today.plusDays(1); !d.isAfter(sunday); d = d.plusDays(1)) {
            if (calendar.dayKind(path, stage, d) == JpathDayKind.STUDY) {
                return false;
            }
        }
        return true;
    }

    // ---- view --------------------------------------------------------------------------------------------------

    private JpathTodayView view(Long userId, JpathDailyPlan plan) {
        JpathUserPath path = pathService.requireActive(userId);
        LocalDateTime localNow = clock.localNow(userId);
        List<JpathDailyTab> rows = new ArrayList<>(dailyTabs.findForUpdateByPlanId(plan.getId()));
        rows.sort(Comparator.comparing(JpathDailyTab::getTab));
        Set<JpathTab> required = requiredTabs(plan);
        Map<JpathTab, JpathTodayTab> tabs = new EnumMap<>(JpathTab.class);
        for (JpathDailyTab t : rows) {
            int due = t.getTab() == JpathTab.SKILL ? 0
                    : (int) userItems.countDue(userId, JpathItemType.valueOf(t.getTab().name()), localNow);
            tabs.put(t.getTab(), new JpathTodayTab(t.getTab(), effectiveTarget(plan, t), t.getNewDone(), due,
                    t.getReviewCap(), t.getReviewDone(), t.getCheckPassedAt() != null, t.getDoneAt() != null,
                    required.contains(t.getTab())));
        }
        boolean study = plan.getDayKind() == JpathDayKind.STUDY;
        JpathSliderValues targets = new JpathSliderValues(target(tabs, JpathTab.VOCAB), target(tabs, JpathTab.KANJI),
                target(tabs, JpathTab.GRAMMAR));
        int minutes = study
                ? JpathPacingEngine.dayMinutes(targets, plan.isKanaPhase(), path.getStudyMode(),
                        path.getDailyMinutes())
                : 0;
        boolean reviewDayHint = study && tabs.values().stream()
                .anyMatch(t -> t.reviewCap() > 0 && t.reviewDue() > 3 * t.reviewCap());
        return new JpathTodayView(plan.getStudyDay(), plan.getDayKind(), plan.isKanaPhase(), plan.isSkillMakeup(),
                List.copyOf(tabs.values()), combinedSteps(plan, path, tabs), minutes,
                heroCards(userId, plan, localNow), plan.getAllDoneAt() != null, reviewDayHint,
                skillDayMissed(userId), resume(userId, plan));
    }

    private static int target(Map<JpathTab, JpathTodayTab> tabs, JpathTab tab) {
        JpathTodayTab t = tabs.get(tab);
        return t == null ? 0 : t.newTarget();
    }

    /**
     * Steps of a combined day (key REVIEW, KANA, VOCAB, KANJI, GRAMMAR, SKILL_QUIZ): SKILL day → REVIEW, SKILL_QUIZ;
     * REVIEW_ONLY → REVIEW; Gộp STUDY → REVIEW then KANA, VOCAB (kana phase) or VOCAB, KANJI, GRAMMAR. Tách STUDY
     * and REST days have none. The first step not complete is active.
     */
    private static List<JpathCombinedStep> combinedSteps(JpathDailyPlan plan, JpathUserPath path,
            Map<JpathTab, JpathTodayTab> tabs) {
        List<String> keys = new ArrayList<>();
        switch (plan.getDayKind()) {
            case SKILL -> keys.addAll(List.of("REVIEW", "SKILL_QUIZ"));
            case REVIEW_ONLY -> keys.add("REVIEW");
            case STUDY -> {
                if (path.getStudyMode() == JpathStudyMode.COMBINED) {
                    keys.add("REVIEW");
                    keys.addAll(plan.isKanaPhase() ? List.of("KANA", "VOCAB") : List.of("VOCAB", "KANJI", "GRAMMAR"));
                }
            }
            default -> {
            }
        }
        List<JpathCombinedStep> out = new ArrayList<>(keys.size());
        boolean activeGiven = false;
        for (String key : keys) {
            int done;
            int total;
            boolean complete;
            if (key.equals("REVIEW")) {
                done = 0;
                total = 0;
                for (JpathTodayTab t : tabs.values()) {
                    done += t.reviewDone();
                    total += Math.min(t.reviewCap(), t.reviewDone() + t.reviewDue());
                }
                complete = done >= total;
            } else if (key.equals("SKILL_QUIZ")) {
                JpathTodayTab skill = tabs.get(JpathTab.SKILL);
                complete = skill != null && (skill.checkPassed() || skill.done());
                done = complete ? 1 : 0;
                total = 1;
            } else {
                JpathTodayTab t = tabs.get(JpathTab.valueOf(key));
                done = t == null ? 0 : t.newDone();
                total = t == null ? 0 : t.newTarget();
                complete = t == null || t.done() || done >= total;
            }
            boolean active = !complete && !activeGiven;
            activeGiven |= active;
            out.add(new JpathCombinedStep(key, done, total, complete, active));
        }
        return out;
    }

    /** Up to 5 cards: the next new words on a study day, the words due for review on a skill or review day. */
    private List<JpathHeroCard> heroCards(Long userId, JpathDailyPlan plan, LocalDateTime localNow) {
        List<String> codes = switch (plan.getDayKind()) {
            case STUDY -> plannedNewItems(userId, JpathTab.VOCAB).stream().limit(HERO_CARDS)
                    .map(JpathItemRef::code).toList();
            case SKILL, REVIEW_ONLY -> userItems.due(userId, JpathItemType.VOCAB, localNow,
                    PageRequest.of(0, HERO_CARDS)).stream().map(JpathUserItem::getItemCode).toList();
            default -> List.of();
        };
        List<JpathHeroCard> out = new ArrayList<>(codes.size());
        for (String code : codes) {
            content.vocab(code).ifPresent(v -> out.add(
                    new JpathHeroCard(v.getCode(), v.getExpression(), v.getReading(), v.getMeaningVi())));
        }
        return out;
    }

    private JpathResumeView resume(Long userId, JpathDailyPlan plan) {
        return openTodaySessions(userId, plan).stream().findFirst()
                .map(s -> new JpathResumeView(s.getId(), s.getPosition(), s.getTotal())).orElse(null);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Open TODAY sessions of {@code plan}, newest first. */
    private List<JpathStudySession> openTodaySessions(Long userId, JpathDailyPlan plan) {
        return sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, JpathDailyPlanInserter.OPEN, TODAY)
                .stream().filter(s -> plan.getId().equals(s.getPlanId())).toList();
    }

    /** NEW items of {@code tab} in the newest open TODAY session on that tab or COMBINED (repeats once). */
    private Optional<List<JpathItemRef>> sessionNewItems(Long userId, JpathDailyPlan plan, JpathTab tab) {
        for (JpathStudySession s : openTodaySessions(userId, plan)) {
            if (!s.getTab().equals(tab.name()) && !s.getTab().equals(COMBINED)) {
                continue;
            }
            Set<JpathItemRef> refs = new LinkedHashSet<>();
            for (JpathQueueStep step : parseQueue(s.getQueueJson())) {
                if (step.kind() == JpathStepKind.NEW && step.tab() == tab && step.itemType() != null
                        && step.itemCode() != null) {
                    refs.add(new JpathItemRef(step.itemType(), step.itemCode()));
                }
            }
            return Optional.of(List.copyOf(refs));
        }
        return Optional.empty();
    }

    private static List<JpathQueueStep> parseQueue(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, JpathQueueStep[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Next new codes of a V/K/G tab; kana-phase vocabulary uses only kana-only words of learned kana. */
    private List<String> newCodes(Long userId, String level, JpathTab tab, boolean kanaPhase, boolean priorityMode,
            int limit) {
        if (tab == JpathTab.VOCAB && kanaPhase) {
            return content.nextKanaOnlyVocab(userId, level, limit, content.learnedKana(userId));
        }
        return content.nextNewCodes(userId, level, JpathItemType.valueOf(tab.name()), limit, priorityMode, null);
    }

    /** Priority mode of the level row (#30), read now so a priority-only flip changes today's next items. */
    private boolean priorityMode(Long pathId, String level) {
        return levels.findById(new JpathUserLevelId(pathId, level)).map(JpathUserLevel::isPriorityMode)
                .orElse(false);
    }

    /** Writes the table rows onto the plan's tabs, skipping {@code kept}; missing rows are added. */
    private void apply(JpathDailyPlan plan, List<JpathDailyTab> tabs, List<JpathDailyTab> fresh, Set<JpathTab> kept) {
        Map<JpathTab, JpathDailyTab> byTab = new EnumMap<>(JpathTab.class);
        tabs.forEach(t -> byTab.put(t.getTab(), t));
        LocalDateTime now = clock.now();
        for (JpathDailyTab row : fresh) {
            if (kept.contains(row.getTab()) || row.getTab() == JpathTab.SKILL && byTab.containsKey(JpathTab.SKILL)) {
                continue;
            }
            JpathDailyTab t = byTab.get(row.getTab());
            if (t == null) {
                row.setPlanId(plan.getId());
                JpathDailyPlanInserter.markZeroTargetDone(row, now);
                dailyTabs.save(row);
                continue;
            }
            t.setNewTarget(row.getNewTarget());
            t.setReviewCap(row.getReviewCap());
            JpathDailyPlanInserter.markZeroTargetDone(t, now);
        }
    }
}
