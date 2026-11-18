package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAnswerRequest;
import com.jlas.jlptpath.dto.JpathAnswerResult;
import com.jlas.jlptpath.dto.JpathAnsweredView;
import com.jlas.jlptpath.dto.JpathCheckIssueRequest;
import com.jlas.jlptpath.dto.JpathCheckNext;
import com.jlas.jlptpath.dto.JpathCheckResult;
import com.jlas.jlptpath.dto.JpathCheckSubmitRequest;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathCompareView;
import com.jlas.jlptpath.dto.JpathCompoundView;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathPairPickRequest;
import com.jlas.jlptpath.dto.JpathPairPickResult;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.dto.JpathReportTargetView;
import com.jlas.jlptpath.dto.JpathSkillCheckView;
import com.jlas.jlptpath.dto.JpathWrongItemView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathCheckSource;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCheckService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSkillPracticeService;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.AnswerKey;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.BuiltCheck;
import com.jlas.jlptpath.service.impl.JpathCheckPicker.Built;
import com.jlas.jlptpath.util.JpathCheckSizing;
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.jlptpath.util.JpathQueueStep;
import com.jlas.jlptpath.util.JpathSrsPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Checks of the JLPT path (plan Tasks 37, 38).
 * <p>
 * Session-bound (kind and tab come from the session's current step, plan decision C13): micro-check, group exercise,
 * kana row, skill-day quiz (FREE_QUIZ step → the #25 source: a V/K/G free quiz on LEARNED items, or the review of
 * the passed kana rows = KANA_ROW with {@code issue_json.skillReview}), grammar practice, grammar review, and SKIP_ONE
 * on the current NEW card. Free (no session): SKIP_ONE, TOPIC_KNOWN, FREE_QUIZ, CONFUSABLE, GRAMMAR_PRACTICE,
 * GRAMMAR_EXTRA, and redo ({@code redoOf}: a FREE_QUIZ on an old check's items).
 * <p>
 * Storage: {@code questions_json} = public {@link JpathQuestionView}s; {@code answers_json} = {@link StoredKeys}
 * (answer keys by question id, each with its grammar bank question code; topic items not asked), never sent;
 * {@code progress_json} = {@link Progress}; {@code result_json} = the {@link JpathCheckResult} returned by every later
 * submit; {@code issue_json} = the public echo of the request (+ {@code skillReview}).
 * <p>
 * Locks: issue and submit take the path lock first ({@code findActiveForUpdate}), then the open level, then the check
 * set, then today's plan and its tabs. pick/answer write only the check set (and study events), so they lock just that
 * row. Today's plan is never created here: it exists once the session does, and a submit after the learner's
 * midnight finds no matching plan and writes none (Review Focus 2).
 */
@Service
public class JpathCheckServiceImpl implements JpathCheckService {

    static final String ISSUED = "ISSUED";
    static final String SUBMITTED = "SUBMITTED";
    static final String EXPIRED = "EXPIRED";
    /** Zone of the server timestamp columns (issued_at, submitted_at), as {@link JpathClock#now()} writes them. */
    private static final java.time.ZoneId SERVER_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
    static final String CHECK_ANSWER = "CHECK_ANSWER";
    static final String SKILL_REVIEW = "skillReview";
    static final String SKILL_DAY = "skillDay";
    static final String SKIPPED = "SKIPPED";
    static final int MAX_ORPHANS = 10;
    static final int GROUP_WORDS = 10;
    /** Skill-day quiz size (#25: one free quiz of at least 10 questions). */
    static final int SKILL_QUIZ_SIZE = 10;
    /** Grammar reviews outside a TODAY round (no plan cap). */
    static final int REVIEW_ROOM_DEFAULT = 10;
    /** F137: topic/week-known checks allowed per learner study day. */
    static final int KNOWN_CHECKS_PER_DAY = 3;
    /** Grace on top of the question timer for network latency. */
    static final long TIMER_GRACE_MS = 2000;

    private static final Set<JpathCheckKind> STEP_KINDS = EnumSet.of(JpathCheckKind.MICRO_CHECK,
            JpathCheckKind.GROUP_EXERCISE, JpathCheckKind.KANA_ROW, JpathCheckKind.FREE_QUIZ,
            JpathCheckKind.GRAMMAR_PRACTICE, JpathCheckKind.GRAMMAR_REVIEW);
    private static final Set<JpathCheckKind> FREE_KINDS = EnumSet.of(JpathCheckKind.SKIP_ONE,
            JpathCheckKind.TOPIC_KNOWN, JpathCheckKind.FREE_QUIZ, JpathCheckKind.CONFUSABLE,
            JpathCheckKind.GRAMMAR_PRACTICE, JpathCheckKind.GRAMMAR_EXTRA, JpathCheckKind.WEEK_KNOWN);
    private static final Set<JpathCheckKind> RETRY_PRACTICE_KINDS = EnumSet.of(JpathCheckKind.MICRO_CHECK,
            JpathCheckKind.GROUP_EXERCISE, JpathCheckKind.CONFUSABLE, JpathCheckKind.GRAMMAR_PRACTICE,
            JpathCheckKind.GRAMMAR_EXTRA, JpathCheckKind.GRAMMAR_REVIEW, JpathCheckKind.SKILL_LESSON,
            JpathCheckKind.SKILL_PRACTICE, JpathCheckKind.SKILL_DICTATION);
    /** Plan 3 skill checks: no item rows, no plan, their own session page. */
    static final Set<JpathCheckKind> SKILL_KINDS = EnumSet.of(JpathCheckKind.SKILL_PRACTICE,
            JpathCheckKind.SKILL_DICTATION, JpathCheckKind.SKILL_SECTION);
    /** Plan 3 #40: a timed section refuses answers this long after its deadline (network latency). */
    static final long SECTION_GRACE_SECONDS = 5;
    static final String SKILL_SESSION_URL = "/learning/jlpt/skills/session/";
    private static final Set<JpathTab> CARD_TABS = EnumSet.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
    /** Kinds bound to one item of the step: a check of another item is never reused for them. */
    private static final Set<JpathCheckKind> ITEM_BOUND = EnumSet.of(JpathCheckKind.SKIP_ONE,
            JpathCheckKind.GRAMMAR_PRACTICE);
    private static final Pattern FILL_IGNORED = Pattern.compile("[\\s\\u3000。]+");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final SecureRandom SEEDS = new SecureRandom();

    /**
     * answers_json: keys by question id; {@code questionCodes} = bank-code map of rows issued before
     * {@link AnswerKey#questionCode()} existed (read only); {@code pending} = topic items not asked (TOPIC_KNOWN).
     */
    record StoredKeys(Map<String, AnswerKey> keys, Map<String, String> questionCodes, List<String> pending) {
        StoredKeys {
            keys = keys == null ? Map.of() : keys;
            questionCodes = questionCodes == null ? Map.of() : questionCodes;
            pending = pending == null ? List.of() : pending;
        }

        StoredKeys(Map<String, AnswerKey> keys, Map<String, String> questionCodes) {
            this(keys, questionCodes, List.of());
        }

        /** The bank question behind {@code qid}: from its key, else from the older map. */
        String questionCode(String qid) {
            AnswerKey key = keys.get(qid);
            return key != null && key.questionCode() != null ? key.questionCode() : questionCodes.get(qid);
        }
    }

    /** progress_json: locked questions and board states. */
    record Progress(Map<String, Answer> answers, Map<String, Board> boards) {
        Progress {
            answers = answers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(answers);
            boards = boards == null ? new LinkedHashMap<>() : new LinkedHashMap<>(boards);
        }
    }

    /**
     * resolved (F147): a practice question first answered wrong and then answered right. The first miss stays the
     * stored answer — it is what scores, rates and sends the item to the notebook; resolved only lets the page move on.
     */
    record Answer(String value, boolean correct, Long elapsedMs, Boolean resolved) {
        Answer(String value, boolean correct, Long elapsedMs) {
            this(value, correct, elapsedMs, null);
        }

        boolean isResolved() {
            return Boolean.TRUE.equals(resolved);
        }
    }

    /** matched / wrongLeft: left ids; errors = wrong picks. */
    record Board(List<String> matched, int errors, List<String> wrongLeft) {
        Board {
            matched = matched == null ? List.of() : List.copyOf(matched);
            wrongLeft = wrongLeft == null ? List.of() : List.copyOf(wrongLeft);
        }
    }

    /** What to issue: kind, tab, level of the items, plan id, and the session step (all null for a free check). */
    private record Spec(JpathCheckKind kind, JpathTab tab, String level, Long planId, JpathStudySession session,
                        List<JpathQueueStep> q, int position, JpathQueueStep step, boolean skillReview,
                        JpathCheckIssueRequest echo, boolean skillDay, boolean skip) {
        Spec(JpathCheckKind kind, JpathTab tab, String level, Long planId, JpathStudySession session,
             List<JpathQueueStep> q, int position, JpathQueueStep step, boolean skillReview,
             JpathCheckIssueRequest echo) {
            this(kind, tab, level, planId, session, q, position, step, skillReview, echo, false, false);
        }
    }

    private final JpathCheckSetRepository checks;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathStudySessionRepository sessions;
    private final JpathDailyPlanRepository plans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathUserItemRepository userItems;
    private final JpathUserSettingsRepository settings;
    private final JpathStudyEventRepository events;
    private final JpathKanaRowRepository kanaRows;
    private final JpathContentReader content;
    private final JpathCheckBuilder builder;
    private final JpathCheckPicker picker;
    private final JpathCheckEffects effects;
    private final JpathClock clock;
    private final Path audioRoot;
    // Plan 3 skill checks (SKILL_*): looked up when used, the skill services sit above this one
    private final ObjectProvider<JpathSkillPracticeService> skillPractice;
    // Owner 06/11 week skip: the coming week of a tab comes from the parts view (looked up when used)
    private ObjectProvider<JpathPartsService> partsService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPartsService(ObjectProvider<JpathPartsService> partsService) {
        this.partsService = partsService;
    }

    /** F133: Premium for the free checks of N4+ (optional so tests building the service without it still work). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.jlptpath.service.JpathPremiumGate premiumGate;

    public JpathCheckServiceImpl(JpathCheckSetRepository checks, JpathUserPathRepository paths,
                                 JpathUserLevelRepository levels, JpathStudySessionRepository sessions,
                                 JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs,
                                 JpathUserItemRepository userItems, JpathUserSettingsRepository settings,
                                 JpathStudyEventRepository events, JpathKanaRowRepository kanaRows,
                                 JpathContentReader content, JpathCheckBuilder builder, JpathCheckPicker picker,
                                 JpathCheckEffects effects, JpathClock clock,
                                 @Value("${content.audio.path:data/content-audio/}") String audioDirectory,
                                 ObjectProvider<JpathSkillPracticeService> skillPractice) {
        this.checks = checks;
        this.paths = paths;
        this.levels = levels;
        this.sessions = sessions;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.userItems = userItems;
        this.settings = settings;
        this.events = events;
        this.kanaRows = kanaRows;
        this.content = content;
        this.builder = builder;
        this.picker = picker;
        this.effects = effects;
        this.clock = clock;
        this.audioRoot = Paths.get(audioDirectory).toAbsolutePath().normalize();
        this.skillPractice = skillPractice;
    }

    // ---- issue ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathCheckView issue(Long userId, JpathCheckIssueRequest r) {
        boolean free = r == null || r.sessionId() == null || r.redoOf() != null;
        // A session-bound request takes its tab from the step (C13), so only a free one must name it.
        if (r == null || r.kind() == null || (free && r.tab() == null && r.redoOf() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        if (free && r.redoOf() == null && !FREE_KINDS.contains(r.kind())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài kiểm tra này cần một lượt học.");
        }
        JpathUserPath path = paths.findActiveForUpdate(userId).orElse(null);
        JpathStudySession session = free ? null : sessions.findByIdAndUserId(r.sessionId(), userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SESSION_NOT_FOUND));
        JpathCheckSet old = r.redoOf() == null ? null : checks.findByTokenAndUserId(r.redoOf(), userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND));
        if (path == null) {
            throw new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH);
        }
        levels.findForUpdate(path.getId(), path.getOpenLevel());
        Spec spec = free ? freeSpec(userId, path, r, old) : sessionSpec(userId, path, session, r);
        if (free && old == null && premiumGate != null) {
            // F133: a free check moves the progress of its level (the open one when it names none): Premium for N4+
            premiumGate.requireLevel(userId, spec.level() != null ? spec.level() : path.getOpenLevel());
        }
        if (spec.skip()) {
            return skipStep(userId, spec);
        }

        if (free && (spec.kind() == JpathCheckKind.TOPIC_KNOWN || spec.kind() == JpathCheckKind.WEEK_KNOWN)) {
            long usedToday = checks.countByUserIdAndKindInAndIssuedAtGreaterThanEqual(userId,
                    List.of(JpathCheckKind.TOPIC_KNOWN, JpathCheckKind.WEEK_KNOWN), serverStartOfToday(userId));
            if (usedToday >= KNOWN_CHECKS_PER_DAY) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Bạn đã dùng đủ 3 lượt 'Tôi biết chủ đề/tuần này' hôm nay. Ngày mai thử lại nhé.");
            }
        }

        JpathCheckSet previous = null;
        if (spec.session() != null) {
            previous = spec.step().checkToken() != null
                    ? checks.findByTokenAndUserId(spec.step().checkToken(), userId).orElse(null)
                    : checks.findFirstByUserIdAndSessionIdAndKindAndTabAndStatusOrderByIdDesc(userId,
                            spec.session().getId(), spec.kind(), spec.tab(), ISSUED).orElse(null);
            // Item-bound kinds: a check of another card of the same session is never reused.
            if (previous != null && ITEM_BOUND.contains(spec.kind()) && spec.step().checkToken() == null
                    && !readList(previous.getItemCodesJson()).contains(spec.step().itemCode())) {
                previous = null;
            }
            if (previous != null && (ISSUED.equals(previous.getStatus())
                    || (SUBMITTED.equals(previous.getStatus()) && !Boolean.FALSE.equals(previous.getPassed())))) {
                if (spec.step().checkToken() == null) {
                    writeToken(spec, previous.getToken());
                }
                return view(previous);
            }
        }

        if (spec.kind() == JpathCheckKind.SKIP_ONE) {
            JpathCheckSet open = requireOneTryToday(userId,
                    spec.session() != null ? spec.step().itemCode() : r.itemCode());
            if (open != null && spec.session() == null) {
                // F131: a second click or tab gets the open check of the item, so one wrong answer is the day's try;
                // nothing was answered on it yet, so its question time starts again now
                open.setIssuedAt(clock.now());
                return view(open);
            }
        }

        JpathUserSettings s = settings.findById(userId).orElse(null);
        boolean timerOn = switch (spec.kind()) {
            case KANA_ROW, GRAMMAR_PRACTICE -> false;
            default -> r.timerOn() != null ? r.timerOn()
                    : spec.tab() == JpathTab.GRAMMAR ? (s == null || s.isGrammarTimerOn())
                    : (s != null && s.isQuizTimerOn());
        };
        long seed = SEEDS.nextLong();
        Built built;
        try {
            built = build(userId, path, spec, old, previous, r, timerOn, seed);
        } catch (ApiException e) {
            // Ruling (Task 38 review): a step with nothing to ask never stalls the session — the server moves on.
            if (spec.session() != null && e.getErrorCode() == ErrorCode.JPATH_CONTENT_NOT_LEARNABLE
                    && (spec.kind() == JpathCheckKind.GRAMMAR_REVIEW || spec.kind() == JpathCheckKind.GRAMMAR_PRACTICE)) {
                return skipStep(userId, spec);
            }
            throw e;
        }
        if (built.check().questions().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }

        String token = UUID.randomUUID().toString();
        ObjectNode echo = JSON.valueToTree(spec.echo());
        if (spec.skillReview()) {
            echo.put(SKILL_REVIEW, true);
        }
        if (spec.skillDay()) {
            echo.put(SKILL_DAY, true);
        }
        JpathCheckSet set = JpathCheckSet.builder()
                .token(token).userId(userId).planId(spec.planId())
                .sessionId(spec.session() == null ? null : spec.session().getId())
                .kind(spec.kind()).tab(spec.tab()).level(spec.level())
                .attemptNo(previous == null ? 1 : previous.getAttemptNo() + 1).status(ISSUED).timerOn(timerOn)
                .issueJson(json(echo))
                .questionsJson(json(built.check().questions()))
                .answersJson(json(new StoredKeys(built.check().answers(), Map.of(), built.pending())))
                .progressJson(json(new Progress(null, null)))
                .itemCodesJson(json(built.check().itemCodes()))
                .issuedAt(clock.now())
                .totalCount(units(built.check().questions(), built.check().answers()))
                .build();
        checks.save(set);
        if (spec.session() != null) {
            writeToken(spec, token);
        }
        return view(set);
    }

    private static void writeToken(Spec spec, String token) {
        List<JpathQueueStep> q = new ArrayList<>(spec.q());
        q.set(spec.position(), spec.step().withToken(token));
        JpathCheckEffects.write(spec.session(), q);
    }

    /** C13: the current step decides the kind and tab; SKIP_ONE needs the current step to be a NEW card. */
    private Spec sessionSpec(Long userId, JpathUserPath path, JpathStudySession session, JpathCheckIssueRequest r) {
        if (!"OPEN".equals(session.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Lượt học này đã kết thúc.");
        }
        List<JpathQueueStep> q = JpathCheckEffects.parse(session.getQueueJson());
        int position = session.getPosition();
        JpathQueueStep step = position < q.size() ? q.get(position) : null;
        String level = path.getOpenLevel();
        JpathCheckKind kind;
        JpathTab tab;
        boolean skillReview = false;
        boolean skillDay = false;
        JpathCheckSource source = null;
        Integer count = null;
        if (r.kind() == JpathCheckKind.SKIP_ONE) {
            if (step == null || step.kind() != JpathStepKind.NEW || step.itemCode() == null || step.repeat()
                    || step.itemType() == null || !CARD_TABS.contains(step.tab())) {
                throw wrongStep();
            }
            kind = JpathCheckKind.SKIP_ONE;
            tab = step.tab();
            level = picker.itemLevel(step.itemType(), step.itemCode());
        } else {
            if (step == null || (step.kind() != JpathStepKind.MICRO_CHECK && step.kind() != JpathStepKind.GROUP_EXERCISE)
                    || step.checkKind() == null || !STEP_KINDS.contains(step.checkKind())) {
                throw wrongStep();
            }
            kind = step.checkKind();
            tab = step.tab();
            switch (kind) {
                case MICRO_CHECK -> {
                    if (!CARD_TABS.contains(tab)) {
                        throw wrongStep();
                    }
                }
                case GRAMMAR_PRACTICE -> {
                    if (step.itemCode() == null) {
                        throw wrongStep();
                    }
                }
                case KANA_ROW -> {
                    if (step.itemCode() == null) {
                        if (tab != JpathTab.SKILL) {
                            throw wrongStep();
                        }
                        skillReview = true;
                    }
                    level = null;
                }
                case FREE_QUIZ -> {
                    if (tab != JpathTab.SKILL) {
                        throw wrongStep();
                    }
                    // #25 via the one shared rule (Task 31): a V/K/G tab with ≥ 10 LEARNED rows, else the kana rows.
                    boolean kanaPhase = session.getPlanId() != null && plans.findById(session.getPlanId())
                            .map(JpathDailyPlan::isKanaPhase).orElse(false);
                    Optional<JpathPathRules.SkillQuizSource> found = JpathPathRules.skillQuizSource(userItems,
                            kanaRows, userId, path.getOpenLevel(), kanaPhase);
                    if (found.isEmpty()) {
                        // #25 "nothing to quiz": the step is done by the server (skipStep).
                        return new Spec(kind, tab, level, session.getPlanId(), session, q, position, step, false,
                                null, true, true);
                    }
                    JpathPathRules.SkillQuizSource src = found.get();
                    skillDay = true;
                    if (src == JpathPathRules.SkillQuizSource.KANA_ROW) {
                        kind = JpathCheckKind.KANA_ROW;
                        skillReview = true;
                        level = null;
                    } else {
                        tab = JpathTab.valueOf(src.name());
                        source = JpathCheckSource.LEARNED;
                        count = SKILL_QUIZ_SIZE;
                    }
                }
                default -> { }
            }
        }
        JpathCheckIssueRequest echo = new JpathCheckIssueRequest(kind, tab, session.getId(), level, null, null,
                source, count, null, r.timerOn(), null, null);
        return new Spec(kind, tab, level, session.getPlanId(), session, q, position, step, skillReview, echo,
                skillDay, false);
    }

    private static ApiException wrongStep() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Bước hiện tại của lượt học không phải bài kiểm tra này.");
    }

    private Spec freeSpec(Long userId, JpathUserPath path, JpathCheckIssueRequest r, JpathCheckSet old) {
        String open = path.getOpenLevel();
        if (old != null) {
            if (old.getKind() == JpathCheckKind.KANA_ROW || !CARD_TABS.contains(old.getTab())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài này không làm lại được.");
            }
            // A redo pays nothing and never completes a skill day: no source, no plan.
            JpathCheckIssueRequest echo = new JpathCheckIssueRequest(JpathCheckKind.FREE_QUIZ, old.getTab(), null,
                    old.getLevel(), null, null, null, JpathCheckPicker.quizSize(r.count()), r.direction(),
                    r.timerOn(), null, r.redoOf());
            return new Spec(JpathCheckKind.FREE_QUIZ, old.getTab(), old.getLevel() == null ? open : old.getLevel(),
                    null, null, List.of(), 0, null, false, echo);
        }
        JpathTab tab = r.tab();
        String level = r.level() == null || r.level().isBlank() ? null : r.level().trim().toUpperCase(Locale.ROOT);
        Long planId = null;
        JpathCheckSource source = r.source();
        Integer count = r.count();
        switch (r.kind()) {
            case SKIP_ONE -> {
                requireCode(r.itemCode());
                level = picker.itemLevel(JpathCheckPicker.sliderType(tab), r.itemCode());
            }
            case TOPIC_KNOWN -> {
                if (tab != JpathTab.VOCAB) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chủ đề chỉ có ở Từ vựng.");
                }
                level = level == null ? open : level;
            }
            case WEEK_KNOWN -> {
                if (tab != JpathTab.KANA) JpathCheckPicker.sliderType(tab);
                level = open;
            }
            case FREE_QUIZ -> {
                JpathCheckPicker.sliderType(tab);
                count = JpathCheckPicker.quizSize(r.count());
                source = source == null ? JpathCheckSource.LEARNED : source;
                if (source == JpathCheckSource.ALL && level == null) {
                    level = open;
                }
                // A free quiz can complete today's skill day (#25): remember today's plan, never lock it here.
                planId = plans.findByUserIdAndStudyDay(userId, clock.today(userId)).map(JpathDailyPlan::getId)
                        .orElse(null);
            }
            case GRAMMAR_PRACTICE, GRAMMAR_EXTRA -> {
                if (tab != JpathTab.GRAMMAR) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài này chỉ có ở Ngữ pháp.");
                }
                requireCode(r.itemCode());
                level = picker.itemLevel(JpathItemType.GRAMMAR, r.itemCode());
            }
            default -> { }
        }
        // F134: a free quiz of every item (source ALL) keeps to the opened levels too; one of the learner's own rows
        // (LEARNED, NOTEBOOK, WEEK) may sit on any level
        boolean levelBound = r.kind() != JpathCheckKind.CONFUSABLE
                && (r.kind() != JpathCheckKind.FREE_QUIZ || source == JpathCheckSource.ALL);
        if (level != null && levelBound
                && JpathPathRules.LEVELS.indexOf(level) > JpathPathRules.LEVELS.indexOf(open)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_LOCKED);
        }
        // The echo is public: it never carries the item code of a check (decision: no item codes before submit).
        JpathCheckIssueRequest echo = new JpathCheckIssueRequest(r.kind(), tab, null, level, r.topicKey(),
                r.unitNo(), source, count, r.direction(), r.timerOn(), null, null);
        return new Spec(r.kind(), tab, level, planId, null, List.of(), 0, null, false, echo);
    }

    private static void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu mục học.");
        }
    }

    private Built build(Long userId, JpathUserPath path, Spec spec, JpathCheckSet old, JpathCheckSet previous,
                        JpathCheckIssueRequest r, boolean timerOn, long seed) {
        String open = path.getOpenLevel();
        JpathCheckIssueRequest e = spec.echo();
        if (old != null) {
            return picker.redo(userId, old, open, e.count(), r.direction(), timerOn, seed);
        }
        return switch (spec.kind()) {
            case KANA_ROW -> {
                if (spec.skillReview()) {
                    yield picker.skillKanaReview(userId, seed);
                }
                List<JpathKana> row = content.kana(null).stream()
                        .filter(k -> spec.step().itemCode().equals(k.getRowCode())).toList();
                yield Built.of(builder.kanaRowCheck(row, seed));
            }
            case GROUP_EXERCISE -> {
                List<String> recent = new ArrayList<>();
                for (int i = spec.position() - 1; i >= 0 && recent.size() < GROUP_WORDS; i--) {
                    JpathQueueStep st = spec.q().get(i);
                    if (isNew(st, JpathTab.VOCAB) && !recent.contains(st.itemCode())) {
                        recent.add(0, st.itemCode());
                    }
                }
                List<JpathVocabulary> words = content.vocabs(recent);
                yield Built.of(builder.groupExercise(words, picker.vocabPool(userId, spec.q(), open, words), timerOn,
                        seed));
            }
            case MICRO_CHECK -> Built.of(microCheck(userId, spec, open, previous, timerOn, seed));
            case SKIP_ONE -> picker.skipOne(userId, JpathCheckPicker.sliderType(spec.tab()),
                    spec.step() != null ? spec.step().itemCode() : r.itemCode(), timerOn, seed);
            case TOPIC_KNOWN -> picker.topic(userId, spec.level(), r.topicKey(), timerOn, seed);
            case WEEK_KNOWN -> {
                JpathPartsService parts = partsService == null ? null : partsService.getIfAvailable();
                JpathPartsService.WeekSkip w = parts == null ? null : parts.weekSkip(userId, spec.tab(), r.count(),
                        spec.tab() == JpathTab.KANA ? r.topicKey() : null);
                if (w == null || !w.offer()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài kiểm tra bỏ qua tuần chưa mở cho bạn.");
                }
                yield picker.week(userId, spec.tab(), spec.level(), w.codes(), timerOn, seed,
                        spec.tab() == JpathTab.KANA && r.topicKey() != null);
            }
            case FREE_QUIZ -> picker.freeQuiz(userId, spec.tab(), spec.level(), open, e.topicKey(), e.unitNo(),
                    e.source(), e.count(), e.direction(), timerOn, seed);
            case CONFUSABLE -> picker.confusable(userId, spec.tab(), open, seed);
            case GRAMMAR_PRACTICE -> picker.practice(spec.step() != null ? spec.step().itemCode() : r.itemCode(),
                    seed);
            case GRAMMAR_EXTRA -> picker.extra(r.itemCode(), timerOn, seed);
            case GRAMMAR_REVIEW -> picker.grammarReview(userId, reviewRoom(spec),
                    previous == null ? Set.of() : JpathCheckPicker.questionCodes(previous), timerOn, seed);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Loại bài kiểm tra này chưa được hỗ trợ.");
        };
    }

    /** Grammar reviews left today (review_cap − review_done of today's GRAMMAR tab); a default outside TODAY. */
    private int reviewRoom(Spec spec) {
        if (!JpathCheckEffects.todayRound(spec.session()) || spec.planId() == null) {
            return REVIEW_ROOM_DEFAULT;
        }
        return dailyTabs.findByPlanIdOrderByTab(spec.planId()).stream().filter(t -> t.getTab() == JpathTab.GRAMMAR)
                .findFirst().map(t -> Math.max(0, t.getReviewCap() - t.getReviewDone())).orElse(0);
    }

    private BuiltCheck microCheck(Long userId, Spec spec, String level, JpathCheckSet previous, boolean timerOn,
                                  long seed) {
        JpathTab tab = spec.tab();
        JpathItemType type = JpathItemType.valueOf(tab.name());
        List<String> codes = new ArrayList<>();
        for (JpathQueueStep st : spec.q()) {
            if (isNew(st, tab) && st.itemType() == type && !codes.contains(st.itemCode())) {
                codes.add(st.itemCode());
            }
        }
        for (String orphan : orphans(userId, type, level)) {
            if (!codes.contains(orphan)) {
                codes.add(orphan);
            }
        }
        return switch (tab) {
            case VOCAB -> {
                List<JpathVocabulary> words = content.vocabs(codes);
                yield builder.vocabCheck(words, picker.vocabPool(userId, spec.q(), level, words), timerOn, seed);
            }
            case KANJI -> {
                List<JpathKanji> kanji = codes.stream().map(content::kanji).flatMap(Optional::stream).toList();
                yield builder.kanjiCheck(kanji, picker.kanjiPool(userId, level, kanji), timerOn, seed);
            }
            default -> {
                // A retry avoids the bank questions of the failed attempt (their codes are in its answer keys).
                Set<String> used = previous == null ? Set.of() : JpathCheckPicker.questionCodes(previous);
                List<JpathGrammar> patterns = codes.stream().map(content::grammar).flatMap(Optional::stream)
                        .toList();
                Map<String, List<JpathGrammarQuestion>> bank = new LinkedHashMap<>();
                patterns.forEach(g -> bank.put(g.getCode(), content.checkQuestions(g.getCode())));
                yield builder.grammarCheck(patterns, bank, used, timerOn, seed);
            }
        };
    }

    private static boolean isNew(JpathQueueStep st, JpathTab tab) {
        return st.kind() == JpathStepKind.NEW && !st.repeat() && st.tab() == tab && st.itemCode() != null;
    }

    /**
     * F117 (owner 14/11, same rule as F111): "Đã biết" / "Tôi biết mẫu này" is one try per item a learner day. A
     * failed try already showed the answer, so a new SKIP_ONE for that item waits until tomorrow. F131: a try is a
     * SKIP_ONE of the item submitted and failed today, or one still open with a wrong answer (the answer was shown
     * even if it was never submitted). Returns the open free check of the item with no answer yet (null when none),
     * for the caller to hand back instead of a new one.
     */
    private JpathCheckSet requireOneTryToday(Long userId, String itemCode) {
        if (itemCode == null) {
            return null;
        }
        LocalDateTime since = serverStartOfToday(userId);
        boolean tried = checks.findByUserIdAndKindAndStatusAndPassedAndSubmittedAtGreaterThanEqual(userId,
                        JpathCheckKind.SKIP_ONE, SUBMITTED, Boolean.FALSE, since).stream()
                .anyMatch(c -> readList(c.getItemCodesJson()).contains(itemCode));
        JpathCheckSet open = null;
        for (JpathCheckSet c : checks.findByUserIdAndKindAndStatusAndIssuedAtGreaterThanEqualOrderByIdAsc(userId,
                JpathCheckKind.SKIP_ONE, ISSUED, since)) {
            if (!readList(c.getItemCodesJson()).contains(itemCode)) {
                continue;
            }
            Map<String, Answer> answers = readProgress(c).answers();
            if (answers.values().stream().anyMatch(a -> !a.correct())) {
                tried = true;
            } else if (answers.isEmpty() && c.getSessionId() == null) {
                open = c;
            }
        }
        if (tried) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Hôm nay bạn đã thử mục này rồi. Ngày mai thử lại nhé, hoặc học như bình thường.");
        }
        return open;
    }

    /**
     * F143: the start of the learner's today as server time (Asia/Ho_Chi_Minh, like {@code issued_at} and
     * {@code submitted_at}), so a learner outside the server's zone gets their own day, as the history page does.
     */
    private LocalDateTime serverStartOfToday(Long userId) {
        return clock.today(userId).atStartOfDay(clock.zone(userId)).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
    }

    /**
     * "Orphans": items learned before today in the open level that no passed check has counted yet (LEARNING,
     * counted_at null), oldest first, at most {@link #MAX_ORPHANS}.
     */
    private List<String> orphans(Long userId, JpathItemType type, String level) {
        LocalDateTime startOfToday = clock.today(userId).atStartOfDay();
        return userItems.findByUserIdAndItemType(userId, type).stream()
                .filter(i -> i.getState() == JpathItemState.LEARNING && i.getCountedAt() == null
                        && !JpathItemStateMachine.TOPIC_PENDING.equals(i.getKnownSource())
                        && level.equals(i.getLevel()) && i.getFirstLearnedAt() != null
                        && i.getFirstLearnedAt().isBefore(startOfToday))
                .sorted(Comparator.comparing(JpathUserItem::getFirstLearnedAt).thenComparing(JpathUserItem::getId))
                .limit(MAX_ORPHANS)
                .map(JpathUserItem::getItemCode)
                .toList();
    }

    // ---- read ----------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public JpathCheckView get(Long userId, String token) {
        return view(owned(userId, token));
    }

    private JpathCheckSet owned(Long userId, String token) {
        if (token == null) {
            throw new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND);
        }
        return checks.findByTokenAndUserId(token, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND));
    }

    private JpathCheckSet ownedForUpdate(Long userId, String token) {
        if (token == null) {
            throw new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND);
        }
        return checks.findByTokenAndUserIdForUpdate(token, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND));
    }

    private JpathCheckView view(JpathCheckSet set) {
        List<JpathQuestionView> questions = readQuestions(set);
        StoredKeys keys = readKeys(set);
        Progress p = readProgress(set);
        List<JpathAnsweredView> answered = new ArrayList<>();
        for (JpathQuestionView qv : questions) {
            AnswerKey key = keys.keys().get(qv.id());
            if (key == null) {
                continue;
            }
            if (qv.board() != null) {
                Board b = p.boards().get(qv.board().boardId());
                if (b != null && (!b.matched().isEmpty() || b.errors() > 0)) {
                    List<List<String>> pairs = b.matched().stream()
                            .map(l -> List.of(l, key.pairs().get(l))).toList();
                    answered.add(new JpathAnsweredView(qv.id(), null, b.errors() == 0, null, null, pairs,
                            b.errors(), null));
                }
                continue;
            }
            Answer a = p.answers().get(qv.id());
            if (a != null && retryOpen(set, a)) {
                continue; // F147: an open practice miss is drawn again as a question, not as an answer
            }
            if (a != null) {
                // A resolved practice miss is complete in the learner's view; retain the first miss for scoring.
                answered.add(new JpathAnsweredView(qv.id(), a.isResolved() ? correctValue(qv, key) : a.value(),
                        a.isResolved() || a.correct(), correctValue(qv, key),
                        key.explanation(), null, 0, report(key, keys.questionCode(qv.id()))));
            }
        }
        JpathSkillCheckView skill = SKILL_KINDS.contains(set.getKind())
                ? skillPractice.getObject().view(set, answeredValues(set, p, questions, keys)) : null;
        return new JpathCheckView(set.getToken(), set.getKind(), set.getTab(), set.getAttemptNo(), set.isTimerOn(),
                set.getStatus(), read(set.getIssueJson(), JpathCheckIssueRequest.class), questions, answered, null,
                skill);
    }

    /**
     * The current step has nothing to ask (skill quiz without a source, grammar review with nothing due, practice
     * on a broken bank): locks today's plan and tabs (after the path and level locks of issue), applies the step's
     * "done" rule and moves the session on. Returns a token-less view with status SKIPPED and the next step.
     */
    private JpathCheckView skipStep(Long userId, Spec spec) {
        JpathStudySession s = spec.session();
        JpathDailyPlan today = null;
        List<JpathDailyTab> tabs = List.of();
        if (JpathCheckEffects.todayRound(s) && s.getPlanId() != null) {
            today = plans.findForUpdate(userId, clock.today(userId))
                    .filter(plan -> plan.getId().equals(s.getPlanId())).orElse(null);
            if (today != null) {
                tabs = dailyTabs.findForUpdateByPlanId(today.getId());
            }
        }
        boolean skillNothing = spec.kind() == JpathCheckKind.FREE_QUIZ;
        com.jlas.jlptpath.dto.JpathCheckNext next = effects.skipStep(userId, s, spec.position(), today, tabs,
                skillNothing);
        return new JpathCheckView(null, spec.kind(), spec.tab(), 0, false, SKIPPED, null, List.of(), List.of(),
                next);
    }

    // ---- pick ----------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathPairPickResult pick(Long userId, String token, JpathPairPickRequest r) {
        JpathCheckSet set = ownedForUpdate(userId, token);
        requireOpen(set);
        if (r == null || r.boardId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        JpathQuestionView qv = readQuestions(set).stream()
                .filter(v -> v.board() != null && r.boardId().equals(v.board().boardId())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Không có bảng nối này."));
        AnswerKey key = readKeys(set).keys().get(qv.id());
        if (!key.pairs().containsKey(r.leftId())
                || qv.board().right().stream().noneMatch(o -> o.id().equals(r.rightId()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lựa chọn không có trên bảng.");
        }
        Progress p = readProgress(set);
        Board b = p.boards().getOrDefault(r.boardId(), new Board(null, 0, null));
        if (b.matched().size() == key.pairs().size() || b.matched().contains(r.leftId())) {
            // A left item already matched changes nothing; the verdict only says whether this pick is its pair.
            return new JpathPairPickResult(r.rightId().equals(key.pairs().get(r.leftId())), b.errors(),
                    b.matched().size() == key.pairs().size(), b.matched());
        }
        set.setLastAnswerAt(clock.now());
        boolean correct = r.rightId().equals(key.pairs().get(r.leftId()));
        List<String> matched = new ArrayList<>(b.matched());
        Set<String> wrongLeft = new LinkedHashSet<>(b.wrongLeft());
        int errors = b.errors();
        if (correct) {
            matched.add(r.leftId());
        } else {
            errors++;
            wrongLeft.add(r.leftId());
        }
        Board next = new Board(matched, errors, new ArrayList<>(wrongLeft));
        p.boards().put(r.boardId(), next);
        set.setProgressJson(json(p));
        boolean complete = matched.size() == key.pairs().size();
        if (complete) {
            for (String left : key.pairs().keySet()) {
                event(set, "c" + set.getId() + "-" + r.boardId() + "-" + left, key.itemType(),
                        key.pairItems().get(left), !wrongLeft.contains(left), null);
            }
        }
        return new JpathPairPickResult(correct, errors, complete, List.copyOf(matched));
    }

    // ---- answer --------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathAnswerResult answer(Long userId, String token, JpathAnswerRequest r) {
        JpathCheckSet set = ownedForUpdate(userId, token);
        if (r == null || r.questionId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        JpathQuestionView qv = readQuestions(set).stream().filter(v -> v.id().equals(r.questionId())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Không có câu hỏi này."));
        if (qv.board() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bảng nối được trả lời bằng từng cặp.");
        }
        StoredKeys keys = readKeys(set);
        AnswerKey key = keys.keys().get(qv.id());
        String questionCode = keys.questionCode(qv.id());
        Progress p = readProgress(set);
        Answer done = p.answers().get(qv.id());
        // F147: a practice question answered wrong stays open for a retry until it is answered right.
        boolean retry = done != null && retryOpen(set, done);
        if (done != null && done.isResolved()) {
            String right = correctValue(qv, key);
            return new JpathAnswerResult(true, right, key.explanation(), null, report(key, questionCode),
                    right == null ? "" : right, !ISSUED.equals(set.getStatus()));
        }
        if (done != null && !retry) {
            // probe L2: a replay (e.g. from another tab) says which answer is kept and whether the check is closed
            JpathAnswerResult kept = result(set, qv, key, questionCode, done);
            return new JpathAnswerResult(kept.correct(), kept.correctValue(), kept.explanation(), kept.compare(),
                    kept.report(), done.value() == null ? "" : done.value(), !ISSUED.equals(set.getStatus()));
        }
        if (r.value() != null && r.value().length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu trả lời quá dài.");
        }
        requireOpen(set);
        LocalDateTime now = clock.now();
        if (pastDeadline(set, now)) {
            throw new ApiException(ErrorCode.JPATH_CHECK_CLOSED, "Đã hết giờ làm bài. Hãy nộp bài.");
        }
        // Floor: the server time since the previous answer (or the issue), less a small grace, is the least a
        // question took; a client value below it (or none) is raised to it.
        LocalDateTime since = set.getLastAnswerAt() != null ? set.getLastAnswerAt() : set.getIssuedAt();
        long serverElapsed = since == null ? 0L : Math.max(0L, Duration.between(since, now).toMillis());
        long floor = r.elapsedMs() == null ? serverElapsed : Math.max(0L, serverElapsed - TIMER_GRACE_MS);
        long elapsed = r.elapsedMs() == null ? floor : Math.max(r.elapsedMs(), floor);
        boolean timedOut = r.value() == null || r.value().isBlank()
                || (qv.timerSec() != null && elapsed > qv.timerSec() * 1000L + TIMER_GRACE_MS);
        boolean correct = !timedOut && correct(qv, key, r.value());
        set.setLastAnswerAt(now);
        if (retry) {
            // A retry never scores again: the first miss is kept; a right retry only marks it resolved.
            if (!correct) {
                return retryResult(key, questionCode);
            }
            p.answers().put(qv.id(), new Answer(done.value(), false, done.elapsedMs(), true));
            set.setProgressJson(json(p));
            return new JpathAnswerResult(true, correctValue(qv, key), key.explanation(), null,
                    report(key, questionCode));
        }
        Answer a = new Answer(r.value(), correct, elapsed);
        p.answers().put(qv.id(), a);
        set.setProgressJson(json(p));
        if (key.itemCode() != null) {
            event(set, "c" + set.getId() + "-" + qv.id(), key.itemType(), key.itemCode(), correct, elapsed);
        }
        if (!correct && RETRY_PRACTICE_KINDS.contains(set.getKind())) {
            return retryResult(key, questionCode); // the right answer stays hidden until the learner finds it
        }
        return result(set, qv, key, questionCode, a);
    }

    /** F147: a practice miss still open for a retry (the set is open and the question not yet answered right). */
    private static boolean retryOpen(JpathCheckSet set, Answer a) {
        return !a.correct() && !a.isResolved() && RETRY_PRACTICE_KINDS.contains(set.getKind())
                && ISSUED.equals(set.getStatus());
    }

    private JpathAnswerResult retryResult(AnswerKey key, String questionCode) {
        return new JpathAnswerResult(false, null, null, null, report(key, questionCode));
    }

    private JpathAnswerResult result(JpathCheckSet set, JpathQuestionView qv, AnswerKey key, String questionCode,
                                     Answer a) {
        return new JpathAnswerResult(a.correct(), correctValue(qv, key), key.explanation(),
                a.correct() ? null : compare(set, qv, key, a.value()), report(key, questionCode));
    }

    static boolean correct(JpathQuestionView qv, AnswerKey key, String value) {
        return switch (qv.type()) {
            case "ORDER" -> key.order() != null && String.join(",", key.order())
                    .equals(value.replaceAll("\\s+", ""));
            // Plan 3 Task 14: every blank EXACT or MINOR (#35)
            case JpathSkillCheckBuilder.DICTATION -> JpathSkillCheckBuilder.dictationCorrect(value, key.text());
            case "FILL_TEXT" -> fillKey(value).equals(fillKey(key.text()))
                    || (key.accepts() != null && key.accepts().stream().anyMatch(s -> fillKey(value).equals(fillKey(s))));
            default -> value.equals(key.correct());
        };
    }

    /** Decision 38: NFKC + katakana → hiragana, then no spaces (also U+3000) and no 。. */
    static String fillKey(String s) {
        return FILL_IGNORED.matcher(JpathKanaText.normalize(s)).replaceAll("");
    }

    private static String correctValue(JpathQuestionView qv, AnswerKey key) {
        return switch (qv.type()) {
            case "ORDER" -> key.order() == null ? null : String.join(",", key.order());
            case "FILL_TEXT" -> key.text();
            default -> key.correct();
        };
    }

    private static JpathReportTargetView report(AnswerKey key, String questionCode) {
        if (key.itemCode() == null || key.itemType() == null) {
            return null;
        }
        if (questionCode != null) {
            return new JpathReportTargetView("QUESTION", questionCode, key.itemType().name(), key.itemCode());
        }
        return new JpathReportTargetView(key.itemType().name(), key.itemCode(), key.itemType().name(),
                key.itemCode());
    }

    /**
     * Side-by-side card: in a confusable drill every wrong answer shows the answer next to the pair partner;
     * elsewhere only when the picked option is the label of the answer's confusable partner.
     */
    private JpathCompareView compare(JpathCheckSet set, JpathQuestionView qv, AnswerKey key, String value) {
        if (key.itemCode() == null
                || (key.itemType() != JpathItemType.VOCAB && key.itemType() != JpathItemType.KANJI)) {
            return null;
        }
        if (set.getKind() == JpathCheckKind.CONFUSABLE && key.partnerCode() != null) {
            JpathCompoundView a = compound(key.itemType(), key.itemCode());
            JpathCompoundView b = compound(key.itemType(), key.partnerCode());
            return a == null || b == null ? null : new JpathCompareView(a, b);
        }
        if (value == null || qv.options() == null) {
            return null;
        }
        String picked = qv.options().stream().filter(o -> o.id().equals(value)).map(JpathOptionView::label)
                .findFirst().orElse(null);
        if (picked == null) {
            return null;
        }
        Optional<String> partner = content.confusablePartner(key.itemType(), key.itemCode());
        if (partner.isEmpty()) {
            return null;
        }
        JpathCompoundView a = compound(key.itemType(), key.itemCode());
        JpathCompoundView b = compound(key.itemType(), partner.get());
        if (a == null || b == null) {
            return null;
        }
        boolean isPartner = picked.equals(b.expression()) || picked.equals(JpathKanaText.firstSense(b.meaning()));
        return isPartner ? new JpathCompareView(a, b) : null;
    }

    private JpathCompoundView compound(JpathItemType type, String code) {
        if (type == JpathItemType.VOCAB) {
            return content.vocab(code).map(v -> new JpathCompoundView(v.getCode(), v.getExpression(), v.getReading(),
                    v.getMeaningVi(), false)).orElse(null);
        }
        return content.kanji(code).map(k -> new JpathCompoundView(k.getCode(), k.getCharacter(), null,
                k.getMeaningsVi(), false)).orElse(null);
    }

    // ---- submit --------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathCheckResult submit(Long userId, String token, JpathCheckSubmitRequest r) {
        // Lock order: path → level → check set → today's plan → tabs.
        JpathUserPath path = paths.findActiveForUpdate(userId).orElse(null);
        if (path != null) {
            levels.findForUpdate(path.getId(), path.getOpenLevel());
        }
        JpathCheckSet set = ownedForUpdate(userId, token);
        if (SUBMITTED.equals(set.getStatus())) {
            return read(set.getResultJson(), JpathCheckResult.class);
        }
        if (EXPIRED.equals(set.getStatus())) {
            throw new ApiException(ErrorCode.JPATH_CHECK_CLOSED);
        }
        List<JpathQuestionView> questions = readQuestions(set);
        StoredKeys keys = readKeys(set);
        Progress p = readProgress(set);
        // a timed section may be handed in unfinished once its time is up: what is not answered counts wrong
        if (!complete(set, questions, keys, p) && !pastDeadline(set, clock.now())) {
            throw new ApiException(ErrorCode.JPATH_CHECK_INCOMPLETE);
        }
        if (path == null) {
            throw new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH);
        }

        LocalDateTime now = clock.now();
        int total = 0;
        int correct = 0;
        Set<String> wrong = new LinkedHashSet<>();
        Map<String, JpathRating> grammarRatings = new HashMap<>();
        for (JpathQuestionView qv : questions) {
            AnswerKey key = keys.keys().get(qv.id());
            if (key == null) {
                continue;
            }
            if (qv.board() != null) {
                Board b = p.boards().get(qv.board().boardId());
                int pairs = key.pairs().size();
                total += pairs;
                // F147: a pair missed once costs one unit, however many wrong picks it took (wrongLeft is distinct).
                correct += Math.max(0, pairs - b.wrongLeft().size());
                b.wrongLeft().forEach(l -> wrong.add(key.pairItems().get(l)));
                continue;
            }
            Answer a = p.answers().get(qv.id());
            total++;
            if (a == null) {
                continue; // a section question left unanswered when the time ran out
            }
            if (a.correct()) {
                correct++;
            } else if (key.itemCode() != null) {
                wrong.add(key.itemCode());
            }
            if (key.itemType() == JpathItemType.GRAMMAR && key.itemCode() != null) {
                JpathRating rating = JpathSrsPolicy.grammarAutoRating(a.correct(),
                        a.elapsedMs() == null ? 0 : a.elapsedMs(), qv.timerSec());
                grammarRatings.merge(key.itemCode(), rating, JpathCheckServiceImpl::lower);
            }
        }
        int percent = total == 0 ? 0 : (int) Math.round(100.0 * correct / total);
        // owner 18/11 kana stage: the kana skip test passes at 80% like the kana row check
        Boolean passed = set.getKind() == JpathCheckKind.WEEK_KNOWN && set.getTab() == JpathTab.KANA
                ? passed(JpathCheckKind.KANA_ROW, correct, total) : passed(set.getKind(), correct, total);
        LocalDateTime last = set.getLastAnswerAt() != null ? set.getLastAnswerAt() : now;
        long spentMs = Duration.between(set.getIssuedAt(), last).toMillis();
        boolean tooFast = total > 0 && spentMs < (long) JpathCheckSizing.TOO_FAST_MS_PER_UNIT * total;

        JsonNode issue = readTree(set.getIssueJson());
        boolean skillReview = issue.path(SKILL_REVIEW).asBoolean(false);
        String source = issue.path("source").isTextual() ? issue.path("source").asText() : null;
        // #25 / D4: only a quiz of ≥ 10 questions on LEARNED or NOTEBOOK items counts for the skill day.
        // The server-chosen skill-day step counts whatever its size (its pool may give fewer than 10 questions).
        // Plan 3 Task 16 removed rule #25: a free quiz never completes a skill day any more
        boolean skillDayQuiz = false;

        List<String> itemCodes = readList(set.getItemCodesJson());
        JpathStudySession session = set.getSessionId() == null ? null
                : sessions.findByIdAndUserId(set.getSessionId(), userId).orElse(null);
        JpathDailyPlan today = null;
        List<JpathDailyTab> tabs = List.of();
        boolean planBound = JpathCheckEffects.todayRound(session) || (session == null && skillDayQuiz);
        if (planBound && set.getPlanId() != null) {
            today = plans.findForUpdate(userId, clock.today(userId))
                    .filter(plan -> plan.getId().equals(set.getPlanId()))
                    .filter(plan -> session != null || plan.getDayKind() == JpathDayKind.SKILL)
                    .orElse(null);
            if (today != null) {
                tabs = dailyTabs.findForUpdateByPlanId(today.getId());
            }
        }
        JpathCheckEffects.Applied applied = effects.apply(set,
                new JpathCheckEffects.Scored(passed, tooFast, itemCodes, wrong, grammarRatings, keys.pending(),
                        skillReview, skillDayQuiz), session, today, tabs);

        boolean rewardEligible = !tooFast && applied.dayCredit();
        boolean skillKind = SKILL_KINDS.contains(set.getKind());
        Long skillSessionId = skillKind ? JpathSkillPracticeServiceImpl.skillSessionId(set) : null;
        JpathCheckNext next = !skillKind ? applied.next() : skillSessionId == null
                ? new JpathCheckNext("BACK", "Quay lại", null, null, null, null)
                : new JpathCheckNext("CONTINUE", "Tiếp tục buổi kỹ năng", SKILL_SESSION_URL + skillSessionId,
                        JpathTab.SKILL, null, null);
        JpathCheckResult result = new JpathCheckResult(set.getToken(), correct, total, percent, passed,
                rewardEligible, tooFast, skillKind ? List.of() : wrongItems(JpathCheckEffects.itemType(set), wrong),
                applied.cpAwarded(), next);
        set.setStatus(SUBMITTED);
        set.setSubmittedAt(now);
        set.setSubmitKey(r == null ? null : r.submitKey());
        set.setCorrectCount(correct);
        set.setTotalCount(total);
        set.setScorePct(total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(100L * correct).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        set.setPassed(passed);
        set.setTooFast(tooFast);
        set.setResultJson(json(result));
        if (skillKind) {
            // the check row is SUBMITTED in this transaction, so the session sees it when it moves on
            skillPractice.getObject().afterSubmit(set);
        }
        return result;
    }

    /**
     * Pass rule per kind: micro-check and kana row ≥ 80% (exact: correct·100 ≥ 80·total, never the rounded
     * percent); skip one = the question right; topic = correct ≥ ceil(0.9·total); other kinds neither pass nor
     * fail (null).
     */
    static Boolean passed(JpathCheckKind kind, int correct, int total) {
        return switch (kind) {
            // Owner 05/11: the check after a part is practice — finishing it completes the part whatever the score
            // (wrong items still go to the notebook); the weekly test is where scores matter.
            case MICRO_CHECK -> total > 0;
            case KANA_ROW -> total > 0 && correct * 100L >= (long) JpathCheckSizing.PASS_PERCENT * total;
            case SKIP_ONE -> total > 0 && correct == total;
            case TOPIC_KNOWN, WEEK_KNOWN -> total > 0 && correct * 10L >= 9L * total;
            // Plan 3 #40: a section passes at a shown (rounded) 32% — the floor 19 of 60 is 31.7% → 32%
            case SKILL_SECTION -> total > 0
                    && Math.round(100.0 * correct / total) >= com.jlas.jlptpath.util.JpathSkillPolicy.SECTION_PASS_PCT;
            default -> null;
        };
    }

    /** A timed Reading section past {@code issue_json.deadlineAt} plus the grace; never true for other checks. */
    static boolean pastDeadline(JpathCheckSet set, LocalDateTime now) {
        if (set.getKind() != JpathCheckKind.SKILL_SECTION) {
            return false;
        }
        JsonNode at = readTree(set.getIssueJson()).path(JpathSkillCheckBuilder.DEADLINE_AT);
        if (!at.isTextual()) {
            return false;
        }
        return now.isAfter(LocalDateTime.parse(at.asText()).plusSeconds(SECTION_GRACE_SECONDS));
    }

    /** Locked question id → the value given (insertion order kept). */
    private static Map<String, String> answeredValues(JpathCheckSet set, Progress p,
                                                       List<JpathQuestionView> questions, StoredKeys keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JpathQuestionView q : questions) {
            Answer a = p.answers().get(q.id());
            if (a == null || retryOpen(set, a)) continue;
            AnswerKey key = keys.keys().get(q.id());
            String resolvedValue = key == null ? a.value() : correctValue(q, key);
            if (a.isResolved() && key != null && JpathSkillCheckBuilder.DICTATION.equals(q.type())) {
                resolvedValue = json(JpathSkillCheckBuilder.blanks(key.text()).stream()
                        .map(JpathSkillCheckBuilder.Blank::answer).toList());
            }
            out.put(q.id(), a.isResolved() ? resolvedValue : a.value());
        }
        return out;
    }

    private static JpathRating lower(JpathRating a, JpathRating b) {
        return JpathSrsPolicy.quality(a) <= JpathSrsPolicy.quality(b) ? a : b;
    }

    private static boolean complete(JpathCheckSet set, List<JpathQuestionView> questions, StoredKeys keys, Progress p) {
        for (JpathQuestionView qv : questions) {
            AnswerKey key = keys.keys().get(qv.id());
            if (key == null) {
                continue;
            }
            if (qv.board() != null) {
                Board b = p.boards().get(qv.board().boardId());
                if (b == null || b.matched().size() < key.pairs().size()) {
                    return false;
                }
            } else if (!p.answers().containsKey(qv.id()) || retryOpen(set, p.answers().get(qv.id()))) {
                return false;
            }
        }
        return true;
    }

    private List<JpathWrongItemView> wrongItems(JpathItemType type, Set<String> codes) {
        List<JpathWrongItemView> out = new ArrayList<>();
        for (String code : codes) {
            if (code == null) {
                continue;
            }
            switch (type) {
                case VOCAB -> content.vocab(code).ifPresent(v -> out.add(new JpathWrongItemView(type, code,
                        v.getExpression(), v.getReading(), v.getMeaningVi())));
                case KANJI -> content.kanji(code).ifPresent(k -> out.add(new JpathWrongItemView(type, code,
                        k.getCharacter(), null, k.getMeaningsVi())));
                case GRAMMAR -> content.grammar(code).ifPresent(g -> out.add(new JpathWrongItemView(type, code,
                        g.getPattern(), null, g.getMeaningVi())));
                case KANA -> content.kanaItem(code).ifPresent(k -> out.add(new JpathWrongItemView(type, code,
                        k.getKana(), k.getRomaji(), null)));
            }
        }
        return out;
    }

    // ---- audio ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Path audio(Long userId, String token, int slot) {
        JpathCheckSet set = owned(userId, token);
        StoredKeys keys = readKeys(set);
        String audioPath = readQuestions(set).stream()
                .filter(v -> v.audioSlot() != null && v.audioSlot() == slot)
                .map(v -> keys.keys().get(v.id()))
                .filter(Objects::nonNull)
                .map(AnswerKey::audioPath)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Path file = audioRoot.resolve(audioPath.replaceFirst("^/+", "")).normalize();
        if (!file.startsWith(audioRoot) || !Files.isRegularFile(file)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return file;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static void requireOpen(JpathCheckSet set) {
        if (!ISSUED.equals(set.getStatus())) {
            throw new ApiException(ErrorCode.JPATH_CHECK_CLOSED);
        }
    }

    /** Units of a check: one per pair on each board, one per other question. */
    static int units(List<JpathQuestionView> questions, Map<String, AnswerKey> keys) {
        int n = 0;
        for (JpathQuestionView qv : questions) {
            AnswerKey key = keys.get(qv.id());
            n += qv.board() != null && key != null && key.pairs() != null ? key.pairs().size() : 1;
        }
        return n;
    }

    private void event(JpathCheckSet set, String clientEventId, JpathItemType type, String code, boolean correct,
                       Long elapsedMs) {
        // Written once: a question locks on its first answer and a board stops taking picks once complete, both
        // under the check-set row lock.
        if (type == null || code == null) {
            return;
        }
        events.save(JpathStudyEvent.builder().userId(set.getUserId()).sessionId(set.getSessionId())
                .clientEventId(clientEventId).itemType(type).itemCode(code).action(CHECK_ANSWER).correct(correct)
                .elapsedMs(elapsedMs == null ? null : (int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs)))
                .studyDay(clock.today(set.getUserId())).build());
    }

    static List<JpathQuestionView> readQuestions(JpathCheckSet set) {
        return read(set.getQuestionsJson(), new TypeReference<List<JpathQuestionView>>() { });
    }

    static StoredKeys readKeys(JpathCheckSet set) {
        return read(set.getAnswersJson(), StoredKeys.class);
    }

    private static Progress readProgress(JpathCheckSet set) {
        return set.getProgressJson() == null ? new Progress(null, null) : read(set.getProgressJson(), Progress.class);
    }

    private static List<String> readList(String json) {
        return json == null ? List.of() : read(json, new TypeReference<List<String>>() { });
    }

    private static JsonNode readTree(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Broken check JSON", e);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write check JSON", e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Broken check JSON", e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Broken check JSON", e);
        }
    }
}
