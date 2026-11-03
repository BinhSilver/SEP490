package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.util.SrsUtil;
import com.jlas.jlptpath.dto.JpathCardView;
import com.jlas.jlptpath.dto.JpathCombinedStep;
import com.jlas.jlptpath.dto.JpathEventBatchResult;
import com.jlas.jlptpath.dto.JpathExampleView;
import com.jlas.jlptpath.dto.JpathItemDetailView;
import com.jlas.jlptpath.dto.JpathSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSessionView;
import com.jlas.jlptpath.dto.JpathStudyEventBatch;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanaRow;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathItemService;
import com.jlas.jlptpath.service.JpathStudyService;
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.jlptpath.util.JpathQueueBuilder;
import com.jlas.jlptpath.util.JpathQueueInput;
import com.jlas.jlptpath.util.JpathQueueInput.DueCard;
import com.jlas.jlptpath.util.JpathQueueInput.KanaRowPlan;
import com.jlas.jlptpath.util.JpathQueueStep;
import com.jlas.jlptpath.util.JpathSrsPolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] Starting, resuming and reading study sessions (queue order: {@link JpathQueueBuilder}).
 *
 * <p>Lock order (Task 31): {@link #start} is not transactional itself. TODAY and EXTRA first run
 * {@link JpathDailyPlanService#ensurePlanExists} (the insert's own transaction); the session is then built in one
 * transaction that takes path {@code findActiveForUpdate} → {@link JpathDailyPlanService#lockToday} (level, then plan)
 * → tabs, the same order as the slider writers.
 */
@Service
public class JpathStudyServiceImpl implements JpathStudyService {

    static final int UPCOMING = 20;
    static final int EXTRA_COUNT = 5;
    static final int LIST_COUNT = 10;
    static final int KANA_ROWS_DEFAULT = 1;
    /** Due cards read per tab; hard cards go past the review cap, so more than the cap is read. */
    static final int DUE_PAGE = 500;
    static final int MAX_COUNT = 200;

    // policy #1 minutes per unit, and a quick review
    static final double MIN_VOCAB = 0.6;
    static final double MIN_KANJI = 1.25;
    static final double MIN_GRAMMAR = 4.0;
    static final double MIN_KANA_ROW = 6.0;
    static final double MIN_REVIEW = 0.15;

    private static final String OPEN = "OPEN";
    private static final String COMPLETED = "COMPLETED";
    private static final String ORDER_BANK = "ORDER";
    private static final String FILL_BANK = "FILL";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<JpathTab> CARD_TABS = List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR,
            JpathTab.KANA);
    private static final List<JpathTab> SLIDER_TABS = List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);

    private final JpathDailyPlanService dailyPlans;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathStudySessionRepository sessions;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathUserItemRepository userItems;
    private final JpathKanaRowRepository kanaRows;
    private final JpathContentReader content;
    private final JpathItemService itemService;
    private final JpathItemStateMachine states;
    private final JpathCatalogService catalog;
    private final JpathClock clock;
    private final JpathStudyEventApplier eventApplier;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public JpathStudyServiceImpl(JpathDailyPlanService dailyPlans, JpathDailyPlanRepository plans,
            JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions, JpathUserPathRepository paths,
            JpathUserLevelRepository levels, JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows,
            JpathContentReader content, JpathItemService itemService, JpathItemStateMachine states,
            JpathCatalogService catalog, JpathClock clock, JpathStudyEventApplier eventApplier,
            PlatformTransactionManager transactionManager) {
        this.dailyPlans = dailyPlans;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.sessions = sessions;
        this.paths = paths;
        this.levels = levels;
        this.userItems = userItems;
        this.kanaRows = kanaRows;
        this.content = content;
        this.itemService = itemService;
        this.states = states;
        this.catalog = catalog;
        this.clock = clock;
        this.eventApplier = eventApplier;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ---- start ---------------------------------------------------------------------------------------------

    @Override
    public JpathSessionView start(Long userId, JpathSessionStartRequest r) {
        if (r == null || r.tab() == null || r.mode() == null
                || r.count() != null && (r.count() < 1 || r.count() > MAX_COUNT)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        if (r.mode() == JpathSessionMode.TODAY || r.mode() == JpathSessionMode.EXTRA) {
            // the insert's own transaction, before the writer transaction below (never under the path lock)
            dailyPlans.ensurePlanExists(userId);
        }
        JpathStudySession s = tx.execute(status -> startLocked(userId, r));
        // the card view reads ~10 statements per card: built after the writer transaction committed
        return readTx.execute(status -> view(userId, s));
    }

    private JpathStudySession startLocked(Long userId, JpathSessionStartRequest r) {
        // The path lock must be the first statement of this transaction: a plain SELECT before it would fix the
        // MySQL REPEATABLE READ snapshot early, and later plain reads would miss rows committed meanwhile.
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        boolean needsPlan = r.mode() == JpathSessionMode.TODAY || r.mode() == JpathSessionMode.EXTRA;
        // lockToday takes the level lock, then the plan lock (path → level → plan → tabs)
        JpathDailyPlan plan = needsPlan ? dailyPlans.lockToday(path) : null;
        JpathUserLevel open = levels.findForUpdate(path.getId(), path.getOpenLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        JpathStudySession s = switch (r.mode()) {
            case TODAY -> today(userId, plan, open, r);
            case EXTRA -> extra(userId, plan, open, r);
            case LOWER_LEVEL -> lowerLevel(userId, open, r);
            case BROWSE -> browse(userId, open, r);
            case NOTEBOOK -> notebook(userId, r);
        };
        return s;
    }

    private JpathStudySession today(Long userId, JpathDailyPlan plan, JpathUserLevel open,
            JpathSessionStartRequest r) {
        Map<JpathTab, JpathDailyTab> tabs = tabsOf(plan);
        Optional<JpathStudySession> existing = openTodaySessions(userId, plan).stream()
                .filter(s -> s.getTab().equals(r.tab().name())).findFirst();
        if (existing.isPresent()) {
            return moveUp(existing.get(), r.startItemCode());
        }
        LocalDateTime localNow = clock.localNow(userId);
        JpathSessionTab st = r.tab();

        Map<JpathTab, List<DueCard>> due = new EnumMap<>(JpathTab.class);
        Map<JpathTab, Integer> room = new EnumMap<>(JpathTab.class);
        List<String> grammarDue = List.of();
        for (JpathTab t : reviewTabs(st)) {
            JpathDailyTab row = tabs.get(t);
            if (row == null) {
                continue;
            }
            int left = Math.max(0, row.getReviewCap() - row.getReviewDone());
            room.put(t, left);
            List<JpathUserItem> dueRows = userItems.due(userId, JpathItemType.valueOf(t.name()), localNow,
                    PageRequest.of(0, DUE_PAGE));
            if (t == JpathTab.GRAMMAR) {
                grammarDue = dueRows.stream().limit(left).map(JpathUserItem::getItemCode).toList();
            } else {
                due.put(t, dueRows.stream().map(this::dueCard).toList());
            }
        }

        boolean skillDay = plan.getDayKind() == JpathDayKind.SKILL;
        Map<JpathTab, List<String>> newCodes = new EnumMap<>(JpathTab.class);
        List<KanaRowPlan> rows = List.of();
        for (JpathTab t : newTabs(st, plan.isKanaPhase())) {
            JpathDailyTab row = tabs.get(t);
            int remaining = row == null ? 0 : Math.max(0, dailyPlans.effectiveTarget(plan, row) - row.getNewDone());
            if (t == JpathTab.KANA) {
                rows = nextKanaRows(userId, remaining);
            } else {
                newCodes.put(t, newCodes(userId, plan.getLevel(), t, plan.isKanaPhase(), open.isPriorityMode(),
                        remaining, rows));
            }
        }
        // Plan 3 Task 16: the skill-day quiz (#25) is gone; skill days are done by skill sessions
        boolean skillQuizNeeded = false;
        List<JpathQueueStep> q = JpathQueueBuilder.build(new JpathQueueInput(st, JpathSessionMode.TODAY,
                plan.getDayKind(), plan.isKanaPhase(), due, room, grammarDue, newCodes,
                practiceCodes(newCodes.getOrDefault(JpathTab.GRAMMAR, List.of())), rows, skillQuizNeeded));
        if (skillDay && skillQuizNeeded) {
            q = kanaRowFallback(userId, plan, q);
        }
        q = heroFirst(q, r.startItemCode());
        if (q.isEmpty()) {
            // nothing left today on this tab: answer with the last finished session instead of a new empty one
            Optional<JpathStudySession> done = sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, COMPLETED,
                    JpathSessionMode.TODAY.name()).stream()
                    .filter(s -> plan.getId().equals(s.getPlanId()) && s.getTab().equals(st.name())).findFirst();
            if (done.isPresent()) {
                return done.get();
            }
        }
        return save(userId, plan.getId(), st, JpathSessionMode.TODAY, q);
    }

    private JpathStudySession extra(Long userId, JpathDailyPlan plan, JpathUserLevel open,
            JpathSessionStartRequest r) {
        JpathTab t = cardTab(r.tab());
        JpathDailyTab row = tabsOf(plan).get(t);
        if (row == null || row.getDoneAt() == null) {
            throw new ApiException(ErrorCode.JPATH_EXTRA_NOT_READY);
        }
        Map<JpathTab, List<String>> newCodes = new EnumMap<>(JpathTab.class);
        List<KanaRowPlan> rows = List.of();
        if (t == JpathTab.KANA) {
            rows = nextKanaRows(userId, r.count() == null ? KANA_ROWS_DEFAULT : r.count());
        } else {
            newCodes.put(t, newCodes(userId, plan.getLevel(), t, plan.isKanaPhase(), open.isPriorityMode(),
                    r.count() == null ? EXTRA_COUNT : r.count(), List.of()));
        }
        return save(userId, plan.getId(), r.tab(), JpathSessionMode.EXTRA, newsQueue(r, newCodes, rows));
    }

    private JpathStudySession lowerLevel(Long userId, JpathUserLevel open, JpathSessionStartRequest r) {
        JpathTab t = sliderTab(r.tab());
        String level = level(r.level());
        if (level == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        int cmp = Integer.compare(levelNo(level), levelNo(open.getLevel()));
        if (cmp < 0) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_LOCKED);
        }
        if (cmp == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        requireReleased(level);
        Map<JpathTab, List<String>> newCodes = new EnumMap<>(JpathTab.class);
        newCodes.put(t, content.nextNewCodes(userId, level, JpathItemType.valueOf(t.name()),
                r.count() == null ? LIST_COUNT : r.count(), false, null));
        return save(userId, null, r.tab(), JpathSessionMode.LOWER_LEVEL, newsQueue(r, newCodes, List.of()));
    }

    private JpathStudySession browse(Long userId, JpathUserLevel open, JpathSessionStartRequest r) {
        JpathTab t = cardTab(r.tab());
        int count = r.count() == null ? LIST_COUNT : r.count();
        Map<JpathTab, List<String>> newCodes = new EnumMap<>(JpathTab.class);
        List<KanaRowPlan> rows = List.of();
        if (t == JpathTab.KANA) {
            rows = r.startItemCode() == null ? nextKanaRows(userId, r.count() == null ? KANA_ROWS_DEFAULT : r.count())
                    : List.of(kanaRowPlan(userId, kanaRowCode(r.startItemCode())));
        } else {
            String level = r.level() == null ? open.getLevel() : level(r.level());
            if (level == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED);
            }
            if (levelNo(level) < levelNo(open.getLevel())) {
                throw new ApiException(ErrorCode.JPATH_LEVEL_LOCKED);
            }
            requireReleased(level);
            newCodes.put(t, browseCodes(userId, level, t, r, count));
        }
        return save(userId, null, r.tab(), JpathSessionMode.BROWSE, newsQueue(r, newCodes, rows));
    }

    private JpathStudySession notebook(Long userId, JpathSessionStartRequest r) {
        List<JpathTab> tabs = switch (r.tab()) {
            case VOCAB -> List.of(JpathTab.VOCAB);
            case KANJI -> List.of(JpathTab.KANJI);
            case COMBINED -> List.of(JpathTab.VOCAB, JpathTab.KANJI);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED);
        };
        Map<JpathTab, List<DueCard>> due = new EnumMap<>(JpathTab.class);
        for (JpathTab t : tabs) {
            due.put(t, userItems.findByUserIdAndItemType(userId, JpathItemType.valueOf(t.name())).stream()
                    .filter(i -> i.getState() == JpathItemState.NOTEBOOK)
                    .map(this::dueCard).toList());
        }
        List<JpathQueueStep> q = JpathQueueBuilder.build(new JpathQueueInput(r.tab(), JpathSessionMode.NOTEBOOK,
                null, false, due, null, null, null, null, null, false));
        return save(userId, null, r.tab(), JpathSessionMode.NOTEBOOK, q);
    }

    // ---- start helpers -------------------------------------------------------------------------------------

    private Map<JpathTab, JpathDailyTab> tabsOf(JpathDailyPlan plan) {
        Map<JpathTab, JpathDailyTab> out = new EnumMap<>(JpathTab.class);
        dailyTabs.findForUpdateByPlanId(plan.getId()).forEach(t -> out.put(t.getTab(), t));
        return out;
    }

    /** Open TODAY sessions of {@code plan}, newest first. */
    private List<JpathStudySession> openTodaySessions(Long userId, JpathDailyPlan plan) {
        return sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, OPEN, JpathSessionMode.TODAY.name())
                .stream().filter(s -> plan.getId().equals(s.getPlanId())).toList();
    }

    /** Resume: a start item still after the position becomes the current step. */
    private JpathStudySession moveUp(JpathStudySession s, String code) {
        if (code == null) {
            return s;
        }
        List<JpathQueueStep> q = parse(s.getQueueJson());
        int first = indexOf(q, code);
        if (first > s.getPosition()) {
            s.setQueueJson(write(JpathQueueBuilder.moveToPosition(q, code, s.getPosition())));
        }
        return s;
    }

    /** TODAY: the start item (a hero card) moves to the front of its tab's new items. */
    private static List<JpathQueueStep> heroFirst(List<JpathQueueStep> q, String code) {
        if (code == null) {
            return q;
        }
        int at = indexOf(q, code);
        if (at < 0 || q.get(at).kind() != JpathStepKind.NEW) {
            return q;
        }
        JpathTab tab = q.get(at).tab();
        for (int i = 0; i < at; i++) {
            if (q.get(i).kind() == JpathStepKind.NEW && q.get(i).tab() == tab) {
                return JpathQueueBuilder.moveToPosition(q, code, i);
            }
        }
        return q;
    }

    private static int indexOf(List<JpathQueueStep> q, String code) {
        for (int i = 0; i < q.size(); i++) {
            if (Objects.equals(q.get(i).itemCode(), code)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Policy #25 on a skill day ({@link JpathPathRules#skillQuizSource}): source KANA_ROW → the quiz step is a
     * KANA_ROW check over the passed rows (item code null, tab SKILL); a V/K/G source keeps the FREE_QUIZ step.
     */
    private List<JpathQueueStep> kanaRowFallback(Long userId, JpathDailyPlan plan, List<JpathQueueStep> q) {
        if (JpathPathRules.skillQuizSource(userItems, kanaRows, userId, plan.getLevel(), plan.isKanaPhase())
                .filter(src -> src == JpathPathRules.SkillQuizSource.KANA_ROW).isEmpty()) {
            return q;
        }
        List<JpathQueueStep> out = new ArrayList<>(q.size());
        for (JpathQueueStep s : q) {
            out.add(s.checkKind() == JpathCheckKind.FREE_QUIZ
                    ? new JpathQueueStep(s.step(), JpathStepKind.MICRO_CHECK, JpathTab.SKILL, null, null,
                            JpathCheckKind.KANA_ROW, null, false)
                    : s);
        }
        return out;
    }

    private JpathQueueInput.DueCard dueCard(JpathUserItem i) {
        return new DueCard(i.getItemType(), i.getItemCode(), i.getState() == JpathItemState.NOTEBOOK, states.hard(i),
                states.retypeDue(i), i.getNextReviewAt());
    }

    private static List<JpathTab> reviewTabs(JpathSessionTab tab) {
        return switch (tab) {
            case COMBINED, SKILL -> CARD_TABS;
            default -> List.of(JpathTab.valueOf(tab.name()));
        };
    }

    private static List<JpathTab> newTabs(JpathSessionTab tab, boolean kanaPhase) {
        return switch (tab) {
            case COMBINED -> kanaPhase ? List.of(JpathTab.KANA, JpathTab.VOCAB) : SLIDER_TABS;
            case SKILL -> List.of();
            default -> List.of(JpathTab.valueOf(tab.name()));
        };
    }

    /**
     * Next new codes of a V/K/G tab (codes with a learner row are never picked). Kana-phase vocabulary: kana-only
     * words written with the kana learned so far plus those of the rows queued in this session.
     */
    private List<String> newCodes(Long userId, String level, JpathTab tab, boolean kanaPhase, boolean priorityMode,
            int limit, List<KanaRowPlan> queuedRows) {
        if (limit <= 0) {
            return List.of();
        }
        if (tab == JpathTab.VOCAB && kanaPhase) {
            return content.nextKanaOnlyVocab(userId, level, limit, learnedKana(userId, queuedRows));
        }
        return content.nextNewCodes(userId, level, JpathItemType.valueOf(tab.name()), limit, priorityMode, null);
    }

    private Set<String> learnedKana(Long userId, List<KanaRowPlan> queuedRows) {
        if (queuedRows.isEmpty()) {
            return content.learnedKana(userId);
        }
        Map<String, String> chars = content.kana(null).stream()
                .collect(Collectors.toMap(JpathKana::getCode, JpathKana::getKana, (a, b) -> a));
        Set<String> learned = new HashSet<>();
        userItems.findByUserIdAndItemType(userId, JpathItemType.KANA).stream()
                .filter(i -> i.getCountedAt() != null)
                .map(i -> chars.get(i.getItemCode())).filter(Objects::nonNull).forEach(learned::add);
        Set<String> rowCodes = queuedRows.stream().map(KanaRowPlan::rowCode).collect(Collectors.toSet());
        content.kana(null).stream().filter(k -> rowCodes.contains(k.getRowCode()))
                .map(JpathKana::getKana).forEach(learned::add);
        return JpathKanaText.expandLearned(learned);
    }

    /** Patterns with a visible ORDER and a visible FILL exercise get the GRAMMAR_PRACTICE step. */
    private Set<String> practiceCodes(List<String> grammarCodes) {
        Set<String> out = new HashSet<>();
        for (String code : grammarCodes) {
            if (!content.questions(code, ORDER_BANK).isEmpty() && !content.questions(code, FILL_BANK).isEmpty()) {
                out.add(code);
            }
        }
        return out;
    }

    /** The next {@code n} kana rows not passed yet (teaching order) with their kana that have no learner row. */
    private List<KanaRowPlan> nextKanaRows(Long userId, int n) {
        if (n <= 0) {
            return List.of();
        }
        Map<String, List<JpathKana>> byRow = kanaByRow();
        Map<String, JpathUserItem> started = kanaRowsOf(userId);
        List<KanaRowPlan> out = new ArrayList<>(n);
        for (JpathKanaRow row : kanaRows.findAll(Sort.by("sortOrder"))) {
            List<JpathKana> kana = byRow.getOrDefault(row.getCode(), List.of());
            boolean passed = !kana.isEmpty() && kana.stream().allMatch(k -> started.containsKey(k.getCode())
                    && started.get(k.getCode()).getCountedAt() != null);
            if (kana.isEmpty() || passed) {
                continue;
            }
            out.add(new KanaRowPlan(row.getCode(), unstarted(kana, started)));
            if (out.size() == n) {
                break;
            }
        }
        return out;
    }

    private KanaRowPlan kanaRowPlan(Long userId, String rowCode) {
        List<JpathKana> kana = kanaByRow().getOrDefault(rowCode, List.of());
        if (kana.isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
        }
        return new KanaRowPlan(rowCode, unstarted(kana, kanaRowsOf(userId)));
    }

    /** {@code HR-…}/{@code KR-…} as given; a kana code ({@code H-ka}) → its row. */
    private String kanaRowCode(String code) {
        if (code.startsWith("HR-") || code.startsWith("KR-")) {
            return kanaRows.findById(code).map(JpathKanaRow::getCode)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        }
        return content.kanaItem(code).map(JpathKana::getRowCode)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
    }

    private Map<String, List<JpathKana>> kanaByRow() {
        Map<String, List<JpathKana>> out = new LinkedHashMap<>();
        content.kana(null).forEach(k -> out.computeIfAbsent(k.getRowCode(), x -> new ArrayList<>()).add(k));
        return out;
    }

    private Map<String, JpathUserItem> kanaRowsOf(Long userId) {
        return userItems.findByUserIdAndItemType(userId, JpathItemType.KANA).stream()
                .collect(Collectors.toMap(JpathUserItem::getItemCode, Function.identity(), (a, b) -> a));
    }

    private static List<String> unstarted(List<JpathKana> kana, Map<String, JpathUserItem> started) {
        return kana.stream().map(JpathKana::getCode).filter(c -> !started.containsKey(c)).toList();
    }

    /**
     * BROWSE codes: new items of the topic (words) or unit (grammar) by sort order, or only the start item; the start
     * item first. An item the learner already started is left out.
     */
    private List<String> browseCodes(Long userId, String level, JpathTab t, JpathSessionStartRequest r, int count) {
        JpathItemType type = JpathItemType.valueOf(t.name());
        String start = r.startItemCode();
        if (start != null) {
            requireLearnable(type, start);
        }
        Set<String> rows = new HashSet<>(userItems.findCodesByUserIdAndItemType(userId, type));
        List<String> codes;
        if (t == JpathTab.VOCAB && r.topicKey() != null) {
            codes = content.visibleVocabulary(level).stream()
                    .filter(v -> r.topicKey().equals(v.getTopicKey()) && content.learnable(v))
                    .map(JpathVocabulary::getCode).toList();
        } else if (t == JpathTab.GRAMMAR && r.unitNo() != null) {
            Map<String, Integer> checks = content.visibleCheckCounts(level);
            codes = content.visibleGrammar(level).stream()
                    .filter(g -> r.unitNo().equals(g.getUnitNo())
                            && content.learnable(g, checks.getOrDefault(g.getCode(), 0)))
                    .map(JpathGrammar::getCode).toList();
        } else if (start != null) {
            codes = List.of(start);
        } else {
            return content.nextNewCodes(userId, level, type, count, false, null);
        }
        List<String> out = new ArrayList<>();
        if (start != null && !rows.contains(start)) {
            out.add(start);
        }
        for (String c : codes) {
            if (out.size() >= count) {
                break;
            }
            if (!rows.contains(c) && !c.equals(start)) {
                out.add(c);
            }
        }
        return out;
    }

    private void requireLearnable(JpathItemType type, String code) {
        boolean learnable = switch (type) {
            case VOCAB -> content.learnable(content.vocab(code).orElseThrow(JpathStudyServiceImpl::itemNotFound));
            case KANJI -> content.learnable(content.kanji(code).orElseThrow(JpathStudyServiceImpl::itemNotFound));
            case GRAMMAR -> content.learnable(content.grammar(code).orElseThrow(JpathStudyServiceImpl::itemNotFound),
                    content.questions(code, JpathContentReader.CHECK_BANK).size());
            case KANA -> content.kanaItem(code).isPresent();
        };
        if (!learnable) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }
    }

    private List<JpathQueueStep> newsQueue(JpathSessionStartRequest r, Map<JpathTab, List<String>> newCodes,
            List<KanaRowPlan> rows) {
        Set<String> practice = practiceCodes(newCodes.getOrDefault(JpathTab.GRAMMAR, List.of()));
        return JpathQueueBuilder.build(new JpathQueueInput(r.tab(), r.mode(), null, false, null, null, null, newCodes,
                practice, rows, false));
    }

    /** An empty queue is stored COMPLETED at once. */
    private JpathStudySession save(Long userId, Long planId, JpathSessionTab tab, JpathSessionMode mode,
            List<JpathQueueStep> q) {
        boolean empty = q.isEmpty();
        return sessions.save(JpathStudySession.builder().userId(userId).planId(planId).tab(tab.name())
                .mode(mode.name()).status(empty ? COMPLETED : OPEN).queueJson(write(q)).position(0).total(q.size())
                .completedAt(empty ? clock.now() : null).build());
    }

    private static JpathTab cardTab(JpathSessionTab tab) {
        return switch (tab) {
            case VOCAB, KANJI, GRAMMAR, KANA -> JpathTab.valueOf(tab.name());
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED);
        };
    }

    private static JpathTab sliderTab(JpathSessionTab tab) {
        return switch (tab) {
            case VOCAB, KANJI, GRAMMAR -> JpathTab.valueOf(tab.name());
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** N1..N5 (any case) upper-cased, otherwise null. */
    private static String level(String level) {
        if (level == null) {
            return null;
        }
        String l = level.trim().toUpperCase(Locale.ROOT);
        return l.matches("N[1-5]") ? l : null;
    }

    /** 5 for N5 … 1 for N1: a larger number is a lower (easier) level. */
    private static int levelNo(String level) {
        return level.charAt(1) - '0';
    }

    private void requireReleased(String level) {
        if (!catalog.isReleased(level)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
    }

    private static ApiException itemNotFound() {
        return new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
    }

    // ---- reads ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<JpathSessionView> current(Long userId, JpathTab tab) {
        Optional<JpathDailyPlan> plan = plans.findByUserIdAndStudyDay(userId, clock.today(userId));
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        return openTodaySessions(userId, plan.get()).stream()
                .filter(s -> tab == null || s.getTab().equals(tab.name())
                        || s.getTab().equals(JpathSessionTab.COMBINED.name()))
                .findFirst()
                .map(s -> view(userId, s));
    }

    @Override
    @Transactional(readOnly = true)
    public JpathSessionView get(Long userId, Long sessionId) {
        JpathStudySession s = sessionId == null ? null : sessions.findByIdAndUserId(sessionId, userId).orElse(null);
        if (s == null) {
            throw new ApiException(ErrorCode.JPATH_SESSION_NOT_FOUND);
        }
        return view(userId, s);
    }

    // ---- events --------------------------------------------------------------------------------------------

    /**
     * Same lock contract as {@link #start}: the session's owner is checked first (another learner's id → not found,
     * nothing written), today's plan is made to exist outside the writer transaction, then
     * {@link JpathStudyEventApplier} takes path → level → plan → tabs → session in one transaction.
     */
    @Override
    public JpathEventBatchResult applyEvents(Long userId, Long sessionId, JpathStudyEventBatch batch) {
        if (sessionId == null || sessions.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_SESSION_NOT_FOUND);
        }
        if (batch == null || batch.events() == null || batch.events().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        dailyPlans.ensurePlanExists(userId);
        JpathStudyEventApplier.Applied applied = tx.execute(status -> eventApplier.apply(userId, sessionId, batch));
        // like start(): the card view is built after the writer transaction committed
        return new JpathEventBatchResult(applied.outcomes(), readTx.execute(status -> view(userId,
                applied.session())));
    }

    // ---- view ----------------------------------------------------------------------------------------------

    JpathSessionView view(Long userId, JpathStudySession s) {
        List<JpathQueueStep> q = parse(s.getQueueJson());
        int position = Math.max(0, Math.min(s.getPosition(), q.size()));
        int end = Math.min(q.size(), position + 1 + UPCOMING);
        List<JpathQueueStep> shown = q.subList(position, end);
        Map<String, JpathUserItem> rows = rowsOf(userId, shown);
        LocalDateTime localNow = clock.localNow(userId);
        JpathCardView current = position < q.size() ? card(userId, q.get(position), rows, localNow) : null;
        List<JpathCardView> upcoming = new ArrayList<>(Math.max(0, end - position - 1));
        for (int i = position + 1; i < end; i++) {
            upcoming.add(card(userId, q.get(i), rows, localNow));
        }
        return new JpathSessionView(s.getId(), JpathSessionTab.valueOf(s.getTab()),
                JpathSessionMode.valueOf(s.getMode()), s.getStatus(), position, q.size(), minutesLeft(q, position),
                segments(q, position), current, upcoming);
    }

    private Map<String, JpathUserItem> rowsOf(Long userId, List<JpathQueueStep> steps) {
        Map<JpathItemType, Set<String>> codes = new EnumMap<>(JpathItemType.class);
        for (JpathQueueStep st : steps) {
            if (st.itemType() != null && st.itemCode() != null && isCard(st)) {
                codes.computeIfAbsent(st.itemType(), x -> new HashSet<>()).add(st.itemCode());
            }
        }
        Map<String, JpathUserItem> out = new HashMap<>();
        codes.forEach((type, set) -> userItems.findByUserIdAndItemTypeAndItemCodeIn(userId, type, set)
                .forEach(i -> out.put(key(type, i.getItemCode()), i)));
        return out;
    }

    private static String key(JpathItemType type, String code) {
        return type.name() + ':' + code;
    }

    private static boolean isCard(JpathQueueStep st) {
        return st.kind() == JpathStepKind.NEW || st.kind() == JpathStepKind.REVIEW
                || st.kind() == JpathStepKind.RETYPE;
    }

    private JpathCardView card(Long userId, JpathQueueStep st, Map<String, JpathUserItem> rows,
            LocalDateTime localNow) {
        if (!isCard(st) || st.itemType() == null || st.itemCode() == null) {
            return new JpathCardView(st.itemType(), st.itemCode(), st.kind(), st.repeat(), false, false, null, null,
                    null, null, null, null, null, List.of(), null, false, List.of(), List.of(), List.of(), null, null,
                    null, null, null, st.checkKind(), st.checkToken());
        }
        JpathUserItem row = rows.get(key(st.itemType(), st.itemCode()));
        boolean hard = row != null && states.hard(row);
        boolean notebook = row != null && row.getState() == JpathItemState.NOTEBOOK;
        boolean retype = st.kind() == JpathStepKind.RETYPE;
        Map<JpathRating, Integer> intervals = st.itemType() == JpathItemType.GRAMMAR ? null
                : JpathSrsPolicy.previewDays(srsState(row), st.kind() == JpathStepKind.NEW, localNow);
        Optional<JpathItemDetailView> found = itemService.detailIfVisible(userId, st.itemType(), st.itemCode());
        if (found.isEmpty()) {
            // hidden after the queue was frozen (#22): the step stays, with no content (expression null)
            return new JpathCardView(st.itemType(), st.itemCode(), st.kind(), st.repeat(), notebook, hard, null,
                    null, null, null, null, null, null, List.of(), null, false, List.of(), List.of(), List.of(), null,
                    intervals, null, null, null, null, null);
        }
        JpathItemDetailView d = found.get();
        return new JpathCardView(st.itemType(), st.itemCode(), st.kind(), st.repeat(), notebook, hard, d.level(),
                d.expression(), retype ? null : d.reading(), retype ? null : d.romaji(), d.meaning(), d.wordType(),
                d.topic(), examples(d.examples(), hard, retype), retype ? null : d.mnemonic(),
                !retype && hard && d.mnemonic() != null,
                d.compounds(), d.kanjiInWord(), hard ? d.confusables() : List.of(), retype ? null : d.audioUrl(),
                intervals, d.kanji(), d.grammar(), d.kana(), null, null);
    }

    /** MAIN examples, plus the first EXTRA one for a hard card; RETYPE drops furigana and audio (the answer). */
    private static List<JpathExampleView> examples(List<JpathExampleView> all, boolean hard, boolean retype) {
        List<JpathExampleView> out = new ArrayList<>();
        boolean extraTaken = false;
        for (JpathExampleView e : all == null ? List.<JpathExampleView>of() : all) {
            boolean main = "MAIN".equals(e.role());
            if (!main && (!hard || extraTaken)) {
                continue;
            }
            extraTaken |= !main;
            out.add(retype ? new JpathExampleView(e.code(), e.role(), e.ja(), null, e.vi(), null) : e);
        }
        return out;
    }

    private static SrsUtil.State srsState(JpathUserItem row) {
        return row == null ? new SrsUtil.State(0, 0, null, null)
                : SrsUtil.State.of(row.getRepetitions(), row.getIntervalDays(), row.getEasiness(),
                        row.getNextReviewAt());
    }

    /**
     * Segments of the queue in order of first appearance: REVIEW (reviews, retypes, the grammar review),
     * SKILL_QUIZ (skill-day check), otherwise the step's tab; done = steps before {@code position}.
     */
    private static List<JpathCombinedStep> segments(List<JpathQueueStep> q, int position) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (int i = 0; i < q.size(); i++) {
            int[] c = counts.computeIfAbsent(segment(q.get(i)), x -> new int[2]);
            c[1]++;
            if (i < position) {
                c[0]++;
            }
        }
        List<JpathCombinedStep> out = new ArrayList<>(counts.size());
        boolean activeGiven = false;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            boolean complete = e.getValue()[0] >= e.getValue()[1];
            boolean active = !complete && !activeGiven;
            activeGiven |= active;
            out.add(new JpathCombinedStep(e.getKey(), e.getValue()[0], e.getValue()[1], complete, active));
        }
        return out;
    }

    private static String segment(JpathQueueStep st) {
        if (st.kind() == JpathStepKind.REVIEW || st.kind() == JpathStepKind.RETYPE
                || st.checkKind() == JpathCheckKind.GRAMMAR_REVIEW) {
            return "REVIEW";
        }
        if (st.tab() == JpathTab.SKILL || st.checkKind() == JpathCheckKind.FREE_QUIZ) {
            return "SKILL_QUIZ";
        }
        return st.tab().name();
    }

    /**
     * ceil(Σ steps from {@code position}): NEW word 0.6, kanji 1.25, pattern 4.0, kana 6.0 / row size (#1);
     * review, retype and repeated card 0.15; checks 0.
     */
    private int minutesLeft(List<JpathQueueStep> q, int position) {
        double total = 0;
        Map<String, Integer> rowSize = null;
        Map<String, String> rowOf = null;
        for (int i = position; i < q.size(); i++) {
            JpathQueueStep st = q.get(i);
            if (!isCard(st)) {
                continue;
            }
            if (st.kind() != JpathStepKind.NEW || st.repeat()) {
                total += MIN_REVIEW;
                continue;
            }
            switch (st.tab()) {
                case VOCAB -> total += MIN_VOCAB;
                case KANJI -> total += MIN_KANJI;
                case GRAMMAR -> total += MIN_GRAMMAR;
                case KANA -> {
                    if (rowSize == null) {
                        List<JpathKana> all = content.kana(null);
                        rowSize = all.stream().collect(Collectors.toMap(JpathKana::getRowCode, k -> 1, Integer::sum));
                        rowOf = all.stream().collect(Collectors.toMap(JpathKana::getCode, JpathKana::getRowCode,
                                (a, b) -> a));
                    }
                    int size = rowSize.getOrDefault(rowOf.get(st.itemCode()), 0);
                    total += size > 0 ? MIN_KANA_ROW / size : MIN_KANA_ROW;
                }
                default -> { }
            }
        }
        return (int) Math.ceil(total - 1e-9);
    }

    // ---- json ----------------------------------------------------------------------------------------------

    static List<JpathQueueStep> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, JpathQueueStep[].class));
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    static String write(List<JpathQueueStep> q) {
        try {
            return JSON.writeValueAsString(q);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
