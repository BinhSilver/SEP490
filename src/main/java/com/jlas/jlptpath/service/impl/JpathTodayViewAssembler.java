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
import com.jlas.jlptpath.entity.JpathDailyTabId;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathPathStatus;
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
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Reads of the daily plan: the "Hôm nay" view, effective targets, planned new items and the missed
 * skill day warning. {@link #view} and {@link #currentPlan} run in a new read-only transaction so they always see a
 * plan the insert transaction has just committed; every part of the view is built from the one plan, tab list and
 * level row read there.
 */
@Component
public class JpathTodayViewAssembler {

    private static final Logger log = LoggerFactory.getLogger(JpathTodayViewAssembler.class);
    private static final int HERO_CARDS = 5;
    private static final String COMBINED = JpathSessionTab.COMBINED.name();
    private static final String TODAY = JpathSessionMode.TODAY.name();

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathStudySessionRepository sessions;
    private final JpathUserItemRepository userItems;
    private final JpathKanaRowRepository kanaRows;
    private final JpathCalendarService calendar;
    private final JpathContentReader content;
    private final JpathClock clock;
    private final ObjectMapper json;

    public JpathTodayViewAssembler(JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions,
            JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows, JpathCalendarService calendar,
            JpathContentReader content, JpathClock clock, ObjectMapper json) {
        this.paths = paths;
        this.levels = levels;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.sessions = sessions;
        this.userItems = userItems;
        this.kanaRows = kanaRows;
        this.calendar = calendar;
        this.content = content;
        this.clock = clock;
        this.json = json;
    }

    /** Today's plan in a fresh read-only transaction (sees a plan committed after the caller's snapshot). */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<JpathDailyPlan> currentPlan(Long userId) {
        return plans.findByUserIdAndStudyDay(userId, clock.today(userId));
    }

    /** The "Hôm nay" view of today's plan, which must exist; no ACTIVE path → JPATH_NO_ACTIVE_PATH. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public JpathTodayView view(Long userId) {
        JpathUserPath path = activePath(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        LocalDate today = clock.today(userId);
        JpathDailyPlan plan = plans.findByUserIdAndStudyDay(userId, today)
                .orElseThrow(() -> JpathDailyPlanInserter.missingPlan(userId, today));
        JpathUserLevel level = openLevel(path);
        List<JpathDailyTab> rows = new ArrayList<>(dailyTabs.findByPlanIdOrderByTab(plan.getId()));
        rows.sort(Comparator.comparing(JpathDailyTab::getTab));
        return view(userId, path, level, plan, rows, today);
    }

    @Transactional(readOnly = true)
    public int effectiveTarget(JpathDailyPlan plan, JpathDailyTab tab) {
        // F148 review: only COMBINED study puts today's kana rows before the words in one session; SPLIT vocabulary
        // draws on kana already learned, so counting those words gave a target the session could not reach
        int kanaRemaining = plan.isKanaPhase() && tab.getTab() == JpathTab.VOCAB && combined(plan.getPathId())
                ? plannedKanaRows(plan, null) : 0;
        return effectiveTarget(plan, tab, priorityMode(plan.getPathId(), plan.getLevel()), kanaRemaining);
    }

    private boolean combined(Long pathId) {
        return pathId != null && paths.findById(pathId).map(p -> p.getStudyMode() == JpathStudyMode.COMBINED).orElse(false);
    }

    static Set<JpathTab> requiredTabs(JpathDailyPlan plan) {
        return switch (plan.getDayKind()) {
            case STUDY -> plan.isKanaPhase() ? EnumSet.of(JpathTab.KANA, JpathTab.VOCAB)
                    : EnumSet.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
            case SKILL -> EnumSet.of(JpathTab.SKILL);
            default -> EnumSet.noneOf(JpathTab.class);
        };
    }

    @Transactional(readOnly = true)
    public List<JpathItemRef> plannedNewItems(Long userId, JpathTab tab) {
        Optional<JpathUserPath> active = activePath(userId);
        if (active.isEmpty() || tab == JpathTab.SKILL) {
            return List.of();
        }
        JpathUserPath path = active.get();
        LocalDate today = clock.today(userId);
        Optional<JpathDailyPlan> stored = plans.findByUserIdAndStudyDay(userId, today);
        if (stored.isPresent()) {
            JpathDailyPlan plan = stored.get();
            return plannedNewItems(userId, plan, dailyTabs.findByPlanIdOrderByTab(plan.getId()), tab,
                    priorityMode(plan.getPathId(), plan.getLevel()));
        }
        JpathUserLevel open = openLevel(path);
        JpathDayRules.DayPlan day = JpathDayRules.dayPlan(content, calendar, plans, path, open, today);
        List<JpathDailyTab> tabRows = JpathDayRules.tabRows(day.kind(), day.kanaPhase(), path.isKanaTab(),
                path.getStudyMode(), open, path.getDailyMinutes());
        int remaining = tabRows.stream()
                .filter(t -> t.getTab() == tab).findFirst().map(JpathDailyTab::getNewTarget).orElse(0);
        int kanaRemaining = day.kanaPhase() && tab == JpathTab.VOCAB
                ? tabRows.stream().filter(t -> t.getTab() == JpathTab.KANA).findFirst()
                        .map(JpathDailyTab::getNewTarget).orElse(0)
                : 0;
        return newRefs(userId, open.getLevel(), tab, day.kanaPhase(), open.isPriorityMode(), remaining, kanaRemaining);
    }

    @Transactional(readOnly = true)
    public boolean skillDayMissed(Long userId) {
        Optional<JpathUserPath> active = activePath(userId);
        if (active.isEmpty()) {
            return false;
        }
        JpathUserPath path = active.get();
        LocalDate today = clock.today(userId);
        // No row for the open level (should not happen): judge it as the STUDY stage rather than fail.
        JpathUserLevel level = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .orElseGet(() -> JpathUserLevel.builder().pathId(path.getId()).level(path.getOpenLevel())
                        .stage(JpathStage.STUDY).build());
        return skillDayMissed(path, level, plans.findByUserIdAndStudyDay(userId, today), today);
    }

    // ---- view --------------------------------------------------------------------------------------------------

    private JpathTodayView view(Long userId, JpathUserPath path, JpathUserLevel level, JpathDailyPlan plan,
            List<JpathDailyTab> rows, LocalDate today) {
        LocalDateTime localNow = clock.localNow(userId);
        boolean priority = plan.getLevel().equals(level.getLevel()) ? level.isPriorityMode()
                : priorityMode(plan.getPathId(), plan.getLevel());
        Set<JpathTab> required = requiredTabs(plan);
        int kanaRemaining = path.getStudyMode() == JpathStudyMode.COMBINED ? plannedKanaRows(plan, rows) : 0;
        Map<JpathTab, JpathTodayTab> tabs = new EnumMap<>(JpathTab.class);
        for (JpathDailyTab t : rows) {
            int due = t.getTab() == JpathTab.SKILL ? 0
                    : (int) userItems.countDue(userId, JpathItemType.valueOf(t.getTab().name()), localNow);
            tabs.put(t.getTab(), new JpathTodayTab(t.getTab(), effectiveTarget(plan, t, priority, kanaRemaining),
                    t.getNewDone(), due, t.getReviewCap(), t.getReviewDone(), t.getCheckPassedAt() != null,
                    t.getDoneAt() != null, required.contains(t.getTab())));
        }
        boolean study = plan.getDayKind() == JpathDayKind.STUDY;
        JpathSliderValues targets = new JpathSliderValues(target(tabs, JpathTab.VOCAB), target(tabs, JpathTab.KANJI),
                target(tabs, JpathTab.GRAMMAR));
        int minutes = study ? JpathPacingEngine.dayMinutes(targets, plan.isKanaPhase(), path.getStudyMode(),
                path.getDailyMinutes()) : 0;
        boolean reviewDayHint = study && tabs.values().stream()
                .anyMatch(t -> t.reviewCap() > 0 && t.reviewDue() > 3 * t.reviewCap());
        List<JpathStudySession> open = openTodaySessions(userId, plan);
        return new JpathTodayView(plan.getStudyDay(), plan.getDayKind(), plan.isKanaPhase(), plan.isSkillMakeup(),
                List.copyOf(tabs.values()), combinedSteps(plan, path, tabs), minutes,
                heroCards(userId, plan, rows, priority, localNow), plan.getAllDoneAt() != null, reviewDayHint,
                skillDayMissed(path, level, Optional.of(plan), today),
                open.stream().findFirst().map(s -> new JpathResumeView(s.getId(), s.getPosition(), s.getTotal()))
                        .orElse(null));
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
                // Plan 3 Task 16: the skill step = DAY skill sessions done of the day's target
                JpathTodayTab skill = tabs.get(JpathTab.SKILL);
                done = skill == null ? 0 : skill.newDone();
                total = skill == null ? 0 : skill.newTarget();
                complete = skill == null || skill.done() || done >= total;
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
    private List<JpathHeroCard> heroCards(Long userId, JpathDailyPlan plan, List<JpathDailyTab> rows,
            boolean priority, LocalDateTime localNow) {
        List<String> codes = switch (plan.getDayKind()) {
            case STUDY -> plannedNewItems(userId, plan, rows, JpathTab.VOCAB, priority).stream().limit(HERO_CARDS)
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

    // ---- shared overloads --------------------------------------------------------------------------------------

    /** min(new target, new done + learnable items not started); kana-phase vocabulary counts kana-only words. */
    private int effectiveTarget(JpathDailyPlan plan, JpathDailyTab tab, boolean priority, int plannedKanaRows) {
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
                : newCodes(plan.getUserId(), plan.getLevel(), tab.getTab(), plan.isKanaPhase(), priority, remaining,
                        plannedKanaRows).size();
        return tab.getNewDone() + Math.min(remaining, available);
    }

    /** From the newest open TODAY session on the tab or COMBINED, else the next codes up to target − done. */
    private List<JpathItemRef> plannedNewItems(Long userId, JpathDailyPlan plan, List<JpathDailyTab> rows,
            JpathTab tab, boolean priority) {
        Optional<List<JpathItemRef>> fromSession = sessionNewItems(userId, plan, tab);
        if (fromSession.isPresent()) {
            return fromSession.get();
        }
        int remaining = rows.stream().filter(t -> t.getTab() == tab).findFirst()
                .map(t -> t.getNewTarget() - t.getNewDone()).orElse(0);
        int kanaRemaining = plan.isKanaPhase() && tab == JpathTab.VOCAB ? plannedKanaRows(plan, rows) : 0;
        return newRefs(userId, plan.getLevel(), tab, plan.isKanaPhase(), priority, remaining, kanaRemaining);
    }

    /** [JLAS-W0108] F148: remaining Kana rows on today's Kana-phase plan so VOCAB counts words unlocked today. */
    private int plannedKanaRows(JpathDailyPlan plan, List<JpathDailyTab> rows) {
        if (!plan.isKanaPhase()) {
            return 0;
        }
        Optional<JpathDailyTab> kana = rows != null
                ? rows.stream().filter(t -> t.getTab() == JpathTab.KANA).findFirst()
                : dailyTabs.findById(new JpathDailyTabId(plan.getId(), JpathTab.KANA));
        if (kana.isEmpty() && rows == null) {
            kana = dailyTabs.findByPlanIdOrderByTab(plan.getId()).stream()
                    .filter(t -> t.getTab() == JpathTab.KANA).findFirst();
        }
        return kana.map(t -> Math.max(0, t.getNewTarget() - t.getNewDone())).orElse(0);
    }

    /**
     * Policy #14 with the same day kinds as the insert (incl. "no new items left"): skill days owed before today,
     * minus today when today is (or will be) the make-up day; warns when no STUDY day is left this week.
     */
    private boolean skillDayMissed(JpathUserPath path, JpathUserLevel level, Optional<JpathDailyPlan> todayPlan,
            LocalDate today) {
        JpathStage stage = level.getStage();
        int owed = JpathDayRules.owedSkillDays(calendar, plans, path, stage, today);
        if (owed <= 0) {
            return false;
        }
        boolean todayPays = todayPlan.map(JpathDailyPlan::isSkillMakeup).orElseGet(() ->
                JpathDayRules.dayPlan(content, calendar, plans, path, level, today).skillMakeup());
        if (todayPays) {
            owed--;
        }
        if (owed <= 0) {
            return false;
        }
        boolean noNewLeft = JpathDayRules.noNewItemsLeft(content, path, level);
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        for (LocalDate d = today.plusDays(1); !d.isAfter(sunday); d = d.plusDays(1)) {
            if (calendar.dayKind(path, stage, d, noNewLeft) == JpathDayKind.STUDY) {
                return false;
            }
        }
        return true;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Optional<JpathUserPath> activePath(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
    }

    /** The open level row; a missing row (should not happen) is an internal error. */
    private JpathUserLevel openLevel(JpathUserPath path) {
        return levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel())).orElseThrow(() -> {
            log.error("Active JLPT path {} has no row for its open level {}", path.getId(), path.getOpenLevel());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
    }

    /** Open TODAY sessions of {@code plan}, newest first. */
    List<JpathStudySession> openTodaySessions(Long userId, JpathDailyPlan plan) {
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
            for (JpathQueueStep step : parseQueue(s)) {
                if (step.kind() == JpathStepKind.NEW && step.tab() == tab && step.itemType() != null
                        && step.itemCode() != null) {
                    refs.add(new JpathItemRef(step.itemType(), step.itemCode()));
                }
            }
            return Optional.of(List.copyOf(refs));
        }
        return Optional.empty();
    }

    private List<JpathQueueStep> parseQueue(JpathStudySession s) {
        String queue = s.getQueueJson();
        if (queue == null || queue.isBlank()) {
            return List.of();
        }
        try {
            return List.of(json.readValue(queue, JpathQueueStep[].class));
        } catch (Exception e) {
            log.warn("Unreadable queue_json of JLPT path session {}: {}", s.getId(), e.getMessage());
            return List.of();
        }
    }

    private List<JpathItemRef> newRefs(Long userId, String level, JpathTab tab, boolean kanaPhase, boolean priority,
            int remaining, int plannedKanaRows) {
        if (remaining <= 0 || tab == JpathTab.KANA || tab == JpathTab.SKILL) {
            return List.of();
        }
        JpathItemType type = JpathItemType.valueOf(tab.name());
        return newCodes(userId, level, tab, kanaPhase, priority, remaining, plannedKanaRows).stream()
                .map(code -> new JpathItemRef(type, code)).toList();
    }

    /**
     * [JLAS-W0108] F148: Next new codes of a V/K/G tab; kana-phase vocabulary includes words covered by learned kana
     * plus today's remaining planned kana rows.
     */
    private List<String> newCodes(Long userId, String level, JpathTab tab, boolean kanaPhase, boolean priority,
            int limit, int plannedKanaRows) {
        if (tab == JpathTab.VOCAB && kanaPhase) {
            Set<String> kana = plannedKanaRows > 0
                    ? content.learnedOrPlannedKana(userId, plannedKanaRows)
                    : content.learnedKana(userId);
            return content.nextKanaOnlyVocab(userId, level, limit, kana);
        }
        return content.nextNewCodes(userId, level, JpathItemType.valueOf(tab.name()), limit, priority, null);
    }

    /** Priority mode of the level row (#30), read now so a priority-only flip changes today's next items. */
    private boolean priorityMode(Long pathId, String level) {
        return levels.findById(new JpathUserLevelId(pathId, level)).map(JpathUserLevel::isPriorityMode)
                .orElse(false);
    }
}
