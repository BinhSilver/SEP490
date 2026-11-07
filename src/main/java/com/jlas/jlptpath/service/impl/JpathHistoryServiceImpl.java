package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathBadgeView;
import com.jlas.jlptpath.dto.JpathCheckSummaryView;
import com.jlas.jlptpath.dto.JpathDayDetailView;
import com.jlas.jlptpath.dto.JpathDayItemView;
import com.jlas.jlptpath.dto.JpathHeatCellView;
import com.jlas.jlptpath.dto.JpathHeatmapView;
import com.jlas.jlptpath.dto.JpathHistoryDayView;
import com.jlas.jlptpath.dto.JpathHistoryView;
import com.jlas.jlptpath.dto.JpathStatsDayView;
import com.jlas.jlptpath.dto.JpathStatsView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathHistoryEventRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathBadgeService;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathHistoryService;
import com.jlas.mission.dto.StreakResponse;
import com.jlas.mission.service.StreakService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] History, stats and heatmap of the JLPT path. Every aggregate is one SQL query grouped by study day for
 * the whole range; "new learned" and "reviewed" are the shared {@link JpathStudyEventRepository#newLearnedByDay} and
 * {@link JpathStudyEventRepository#reviewedByDay} helpers (the same numbers as the day summary). Day kind: the stored
 * plan, else the calendar on days of the path (creation day to today), else null. No path lock is taken.
 */
@Service
public class JpathHistoryServiceImpl implements JpathHistoryService {

    /** Server zone of ordinary timestamp columns (submitted_at), like Hibernate and {@link JpathClockImpl}. */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Actions that count on the heatmap. */
    private static final Set<String> HEAT_ACTIONS = Set.of("FIRST_RATE", "REVIEW_RATE", "RETYPE", "SKIP_KNOWN",
            "MARK_KNOWN");

    private static final String CHECK_ANSWER = "CHECK_ANSWER";

    /** Check kinds the learner can take again from the history. */
    private static final Set<JpathCheckKind> REDOABLE = EnumSet.of(JpathCheckKind.MICRO_CHECK,
            JpathCheckKind.FREE_QUIZ, JpathCheckKind.CONFUSABLE, JpathCheckKind.GRAMMAR_PRACTICE,
            JpathCheckKind.GRAMMAR_EXTRA, JpathCheckKind.GRAMMAR_REVIEW);

    /** Tabs that hold learnable items (one per item type). */
    private static final JpathTab[] ITEM_TABS = {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR, JpathTab.KANA};

    private final JpathClock clock;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathDailyPlanRepository plans;
    private final JpathStudyEventRepository events;
    private final JpathHistoryEventRepository historyEvents;
    private final JpathCheckSetRepository checkSets;
    private final JpathUserItemRepository userItems;
    private final JpathContentReader content;
    private final JpathCalendarService calendar;
    private final StreakService streaks;
    private final JpathBadgeService badges;

    public JpathHistoryServiceImpl(JpathClock clock, JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathDailyPlanRepository plans, JpathStudyEventRepository events,
            JpathHistoryEventRepository historyEvents, JpathCheckSetRepository checkSets,
            JpathUserItemRepository userItems, JpathContentReader content, JpathCalendarService calendar,
            StreakService streaks, JpathBadgeService badges) {
        this.clock = clock;
        this.paths = paths;
        this.levels = levels;
        this.plans = plans;
        this.events = events;
        this.historyEvents = historyEvents;
        this.checkSets = checkSets;
        this.userItems = userItems;
        this.content = content;
        this.calendar = calendar;
        this.streaks = streaks;
        this.badges = badges;
    }

    // ---- history -----------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public JpathHistoryView history(Long userId, LocalDate from, LocalDate to) {
        LocalDate today = clock.today(userId);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày bắt đầu phải trước ngày kết thúc.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_HISTORY_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Chỉ xem được tối đa " + MAX_HISTORY_DAYS + " ngày một lần.");
        }
        Map<LocalDate, JpathDailyPlan> planByDay = plansOf(userId, start, end);
        Map<LocalDate, Integer> newLearned = events.newLearnedByDay(userId, start, end);
        Map<LocalDate, Integer> reviewed = events.reviewedByDay(userId, start, end);
        Map<LocalDate, List<JpathCheckSummaryView>> checks = checksByDay(userId, start, end);
        TreeSet<LocalDate> days = new TreeSet<>(planByDay.keySet());
        days.addAll(checks.keySet());
        for (Object[] row : historyEvents.countByDayAndAction(userId, start, end)) {
            days.add((LocalDate) row[0]);
        }
        KindResolver kinds = kindResolver(userId, today);
        List<JpathHistoryDayView> out = new ArrayList<>(days.size());
        for (LocalDate day : days.descendingSet()) {
            JpathDailyPlan plan = planByDay.get(day);
            out.add(new JpathHistoryDayView(day, kinds.kind(day, plan), newLearned.getOrDefault(day, 0),
                    reviewed.getOrDefault(day, 0), plan != null && plan.getAllDoneAt() != null, cpOf(plan),
                    checks.getOrDefault(day, List.of())));
        }
        return new JpathHistoryView(start, end, out, today);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathDayDetailView day(Long userId, LocalDate day) {
        JpathDailyPlan plan = plans.findByUserIdAndStudyDay(userId, day).orElse(null);
        List<JpathStudyEvent> dayEvents = historyEvents.findByUserIdAndStudyDayOrderByIdAsc(userId, day);
        Map<String, JpathStudyEvent> first = new LinkedHashMap<>();
        Map<String, String> lastAction = new HashMap<>();
        Map<String, JpathRating> lastRating = new HashMap<>();
        Map<String, Boolean> lastCorrect = new HashMap<>();
        Set<String> wrong = new LinkedHashSet<>();
        for (JpathStudyEvent e : dayEvents) {
            String key = key(e.getItemType(), e.getItemCode());
            first.putIfAbsent(key, e);
            lastAction.put(key, e.getAction());
            if (e.getRating() != null) {
                lastRating.put(key, e.getRating());
            }
            if (e.getCorrect() != null) {
                lastCorrect.put(key, e.getCorrect());
            }
            if (isWrong(e)) {
                wrong.add(key);
            }
        }
        Map<String, String[]> labels = labels(first.values());
        Map<String, JpathDayItemView> items = new LinkedHashMap<>();
        for (Map.Entry<String, JpathStudyEvent> entry : first.entrySet()) {
            String key = entry.getKey();
            JpathStudyEvent e = entry.getValue();
            String[] label = labels.getOrDefault(key, new String[] {e.getItemCode(), null});
            items.put(key, new JpathDayItemView(e.getItemType(), e.getItemCode(), label[0], label[1],
                    lastAction.get(key), lastRating.get(key), lastCorrect.get(key)));
        }
        List<JpathDayItemView> wrongItems = wrong.stream().map(items::get).toList();
        LocalDate today = clock.today(userId);
        KindResolver kinds = kindResolver(userId, today);
        return new JpathDayDetailView(day, kinds.kind(day, plan), plan != null && plan.getAllDoneAt() != null,
                cpOf(plan), List.copyOf(items.values()), checksByDay(userId, day, day).getOrDefault(day, List.of()),
                wrongItems, today);
    }

    // ---- stats -------------------------------------------------------------------------------------------------

    /**
     * Not one transaction: the streak refresh writes (it may open a freeze offer) in its own transaction first, the
     * reads after it each run in their repository's read transaction.
     */
    @Override
    public JpathStatsView stats(Long userId, int range) {
        if (range != 7 && range != 30) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chỉ xem thống kê 7 hoặc 30 ngày.");
        }
        StreakResponse streak = streaks.refresh(userId);
        LocalDate today = clock.today(userId);
        LocalDate start = today.minusDays(range - 1L);

        Map<JpathTab, Double> accuracy = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : ITEM_TABS) {
            accuracy.put(tab, null);
        }
        for (Object[] row : historyEvents.checkAnswersByItemType(userId, start, today)) {
            accuracy.put(JpathTab.valueOf(((JpathItemType) row[0]).name()),
                    percent(number(row[2]), number(row[1])));
        }
        Map<JpathTab, Long> learned = new EnumMap<>(JpathTab.class);
        Map<JpathTab, Long> known = new EnumMap<>(JpathTab.class);
        Map<JpathTab, Long> notebook = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : ITEM_TABS) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            learned.put(tab, userItems.countByUserIdAndItemTypeAndCountedAtIsNotNull(userId, type));
            known.put(tab, userItems.countByUserIdAndItemTypeAndState(userId, type, JpathItemState.KNOWN));
            notebook.put(tab, userItems.countByUserIdAndItemTypeAndState(userId, type, JpathItemState.NOTEBOOK));
        }

        Map<LocalDate, Integer> newLearned = events.newLearnedByDay(userId, start, today);
        Map<LocalDate, Integer> reviewed = events.reviewedByDay(userId, start, today);
        Map<LocalDate, long[]> answers = new HashMap<>();
        for (Object[] row : historyEvents.countByDayAndAction(userId, start, today)) {
            if (CHECK_ANSWER.equals(row[1])) {
                answers.put((LocalDate) row[0], new long[] {number(row[3]), number(row[2])});
            }
        }
        List<JpathStatsDayView> days = new ArrayList<>(range);
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            long[] a = answers.get(day);
            days.add(new JpathStatsDayView(day, newLearned.getOrDefault(day, 0), reviewed.getOrDefault(day, 0),
                    a == null ? null : percent(a[0], a[1])));
        }
        return new JpathStatsView(range, accuracy, learned, known, notebook, streak.currentStreak(),
                streak.longestStreak(), days, badges.badges(userId));
    }

    // ---- heatmap -----------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public JpathHeatmapView heatmap(Long userId, Integer months) {
        int span = months == null ? DEFAULT_HEATMAP_MONTHS : Math.max(1, Math.min(12, months));
        LocalDate today = clock.today(userId);
        LocalDate start = today.withDayOfMonth(1).minusMonths(span - 1L);
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (Object[] row : historyEvents.countByDayAndAction(userId, start, today)) {
            if (HEAT_ACTIONS.contains((String) row[1])) {
                counts.merge((LocalDate) row[0], (int) number(row[2]), Integer::sum);
            }
        }
        Map<LocalDate, JpathDailyPlan> planByDay = plansOf(userId, start, today);
        KindResolver kinds = kindResolver(userId, today);
        List<JpathHeatCellView> cells = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            int count = counts.getOrDefault(day, 0);
            cells.add(new JpathHeatCellView(day, count, JpathHistoryService.heatLevel(count),
                    kinds.kind(day, planByDay.get(day))));
        }
        return new JpathHeatmapView(start, today, cells);
    }

    @Override
    public List<JpathBadgeView> badges(Long userId) {
        return badges.badges(userId);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Map<LocalDate, JpathDailyPlan> plansOf(Long userId, LocalDate from, LocalDate to) {
        return plans.findByUserIdAndStudyDayBetween(userId, from, to).stream()
                .collect(Collectors.toMap(JpathDailyPlan::getStudyDay, Function.identity(), (a, b) -> a));
    }


    /** Submitted checks per learner study day (submit time moved from the server zone to the learner's). */
    private Map<LocalDate, List<JpathCheckSummaryView>> checksByDay(Long userId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(userId);
        LocalDateTime start = from.atStartOfDay(zone).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
        LocalDateTime end = to.plusDays(1).atStartOfDay(zone).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
        Map<LocalDate, List<JpathCheckSummaryView>> out = new HashMap<>();
        for (Object[] row : checkSets.findSubmittedSummariesBetween(userId, start, end)) {
            LocalDateTime submittedAt = (LocalDateTime) row[5];
            LocalDate day = submittedAt.atZone(SERVER_ZONE).withZoneSameInstant(zone).toLocalDate();
            JpathCheckKind kind = (JpathCheckKind) row[1];
            BigDecimal score = (BigDecimal) row[3];
            out.computeIfAbsent(day, d -> new ArrayList<>()).add(new JpathCheckSummaryView((String) row[0], kind,
                    (JpathTab) row[2], score == null ? null : score.doubleValue(), (Boolean) row[4], submittedAt,
                    REDOABLE.contains(kind)));
        }
        return out;
    }

    /** Display label and reading of each touched item, one batch read per item type. */
    private Map<String, String[]> labels(Iterable<JpathStudyEvent> touched) {
        Map<JpathItemType, List<String>> codes = new EnumMap<>(JpathItemType.class);
        for (JpathStudyEvent e : touched) {
            codes.computeIfAbsent(e.getItemType(), t -> new ArrayList<>()).add(e.getItemCode());
        }
        Map<String, String[]> out = new HashMap<>();
        codes.forEach((type, list) -> {
            switch (type) {
                case VOCAB -> {
                    for (JpathVocabulary v : content.vocabs(list)) {
                        out.put(key(type, v.getCode()), new String[] {v.getExpression(), v.getReading()});
                    }
                }
                case KANJI -> {
                    for (JpathKanji k : content.kanjis(list)) {
                        out.put(key(type, k.getCode()), new String[] {k.getCharacter(), k.getHanViet()});
                    }
                }
                case GRAMMAR -> {
                    for (JpathGrammar g : content.grammars(list)) {
                        out.put(key(type, g.getCode()), new String[] {g.getPattern(), null});
                    }
                }
                case KANA -> {
                    for (JpathKana k : content.kanaItems(list)) {
                        out.put(key(type, k.getCode()), new String[] {k.getKana(), k.getRomaji()});
                    }
                }
            }
        });
        return out;
    }

    /** A wrong CHECK_ANSWER, or a rating of "Chưa nhớ". */
    private static boolean isWrong(JpathStudyEvent e) {
        return (CHECK_ANSWER.equals(e.getAction()) && Boolean.FALSE.equals(e.getCorrect()))
                || e.getRating() == JpathRating.FORGOT;
    }

    private static String key(JpathItemType type, String code) {
        return type.name() + ':' + code;
    }

    private static int cpOf(JpathDailyPlan plan) {
        return plan == null || plan.getCpAwarded() == null ? 0 : plan.getCpAwarded();
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** {@code part / whole} as a percentage with one decimal; null when {@code whole} is 0. */
    private static Double percent(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private KindResolver kindResolver(Long userId, LocalDate today) {
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElse(null);
        if (path == null) {
            return new KindResolver(calendar, null, null, null, today);
        }
        JpathStage stage = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(JpathUserLevel::getStage).orElse(JpathStage.STUDY);
        LocalDate created = path.getCreatedAt() == null ? null : path.getCreatedAt().toLocalDate();
        return new KindResolver(calendar, path, stage, created, today);
    }

    /** Day kind: the stored plan, else the calendar within [path created, today], else null. */
    private record KindResolver(JpathCalendarService calendar, JpathUserPath path, JpathStage stage,
                                LocalDate created, LocalDate today) {

        JpathDayKind kind(LocalDate day, JpathDailyPlan plan) {
            if (plan != null && plan.getDayKind() != null) {
                return plan.getDayKind();
            }
            if (path == null || day.isAfter(today) || (created != null && day.isBefore(created))) {
                return null;
            }
            return calendar.dayKind(path, stage, day);
        }
    }
}
