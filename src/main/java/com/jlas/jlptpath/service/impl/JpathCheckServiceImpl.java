package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAnswerRequest;
import com.jlas.jlptpath.dto.JpathAnswerResult;
import com.jlas.jlptpath.dto.JpathAnsweredView;
import com.jlas.jlptpath.dto.JpathCheckIssueRequest;
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
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCheckService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.AnswerKey;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.BuiltCheck;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.DistractorPool;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Session-bound checks (plan Task 37): micro-check, group exercise and kana row.
 * <p>
 * Storage: {@code questions_json} = public {@link JpathQuestionView}s; {@code answers_json} = {@link StoredKeys}
 * (answer keys by question id + grammar bank question code by question id), never sent; {@code progress_json} =
 * {@link Progress}; {@code result_json} = the {@link JpathCheckResult} returned by every later submit.
 * <p>
 * Locks: writers that touch path data (issue, submit) take the path lock first ({@code findActiveForUpdate}), then
 * the open level, then the check set, then today's plan and its tabs. pick/answer write only the check set (and
 * study events), so they lock just that row. Today's plan is never created here (it exists once the session does).
 */
@Service
public class JpathCheckServiceImpl implements JpathCheckService {

    static final String ISSUED = "ISSUED";
    static final String SUBMITTED = "SUBMITTED";
    static final String EXPIRED = "EXPIRED";
    static final String CHECK_ANSWER = "CHECK_ANSWER";
    static final int MAX_ORPHANS = 10;
    static final int GROUP_WORDS = 10;
    /** Grace on top of the question timer for network latency. */
    static final long TIMER_GRACE_MS = 2000;

    private static final Set<JpathCheckKind> SESSION_KINDS =
            EnumSet.of(JpathCheckKind.MICRO_CHECK, JpathCheckKind.GROUP_EXERCISE, JpathCheckKind.KANA_ROW);
    private static final Set<JpathTab> MICRO_CHECK_TABS = EnumSet.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
    private static final Pattern FILL_IGNORED = Pattern.compile("[\\s\\u3000。]+");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom SEEDS = new SecureRandom();

    /** answers_json: keys by question id; grammar bank question code by question id (for usedCodes and reports). */
    record StoredKeys(Map<String, AnswerKey> keys, Map<String, String> questionCodes) {
        StoredKeys {
            keys = keys == null ? Map.of() : keys;
            questionCodes = questionCodes == null ? Map.of() : questionCodes;
        }
    }

    /** progress_json: locked questions and board states. */
    record Progress(Map<String, Answer> answers, Map<String, Board> boards) {
        Progress {
            answers = answers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(answers);
            boards = boards == null ? new LinkedHashMap<>() : new LinkedHashMap<>(boards);
        }
    }

    record Answer(String value, boolean correct, Long elapsedMs) {
    }

    /** matched / wrongLeft: left ids; errors = wrong picks. */
    record Board(List<String> matched, int errors, List<String> wrongLeft) {
        Board {
            matched = matched == null ? List.of() : List.copyOf(matched);
            wrongLeft = wrongLeft == null ? List.of() : List.copyOf(wrongLeft);
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
    private final JpathContentReader content;
    private final JpathCheckBuilder builder;
    private final JpathCheckEffects effects;
    private final JpathClock clock;
    private final Path audioRoot;

    public JpathCheckServiceImpl(JpathCheckSetRepository checks, JpathUserPathRepository paths,
                                 JpathUserLevelRepository levels, JpathStudySessionRepository sessions,
                                 JpathDailyPlanRepository plans, JpathDailyTabRepository dailyTabs,
                                 JpathUserItemRepository userItems, JpathUserSettingsRepository settings,
                                 JpathStudyEventRepository events, JpathContentReader content,
                                 JpathCheckBuilder builder, JpathCheckEffects effects, JpathClock clock,
                                 @Value("${content.audio.path:data/content-audio/}") String audioDirectory) {
        this.checks = checks;
        this.paths = paths;
        this.levels = levels;
        this.sessions = sessions;
        this.plans = plans;
        this.dailyTabs = dailyTabs;
        this.userItems = userItems;
        this.settings = settings;
        this.events = events;
        this.content = content;
        this.builder = builder;
        this.effects = effects;
        this.clock = clock;
        this.audioRoot = Paths.get(audioDirectory).toAbsolutePath().normalize();
    }

    // ---- issue ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathCheckView issue(Long userId, JpathCheckIssueRequest r) {
        if (r == null || r.kind() == null || r.tab() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        if (!SESSION_KINDS.contains(r.kind())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Loại bài kiểm tra này chưa được hỗ trợ.");
        }
        if (r.sessionId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài kiểm tra này cần một lượt học.");
        }
        JpathUserPath path = paths.findActiveForUpdate(userId).orElse(null);
        JpathStudySession session = ownedSession(r.sessionId(), userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SESSION_NOT_FOUND));
        if (path == null) {
            throw new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH);
        }
        levels.findForUpdate(path.getId(), path.getOpenLevel());
        if (!"OPEN".equals(session.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Lượt học này đã kết thúc.");
        }
        List<JpathQueueStep> q = JpathCheckEffects.parse(session.getQueueJson());
        int position = session.getPosition();
        JpathQueueStep step = position < q.size() ? q.get(position) : null;
        if (step == null || (step.kind() != JpathStepKind.MICRO_CHECK && step.kind() != JpathStepKind.GROUP_EXERCISE)
                || step.checkKind() == null || !SESSION_KINDS.contains(step.checkKind())
                || (step.checkKind() == JpathCheckKind.MICRO_CHECK && !MICRO_CHECK_TABS.contains(step.tab()))
                || (step.checkKind() == JpathCheckKind.KANA_ROW && step.itemCode() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bước hiện tại của lượt học không phải bài kiểm tra này.");
        }
        // Plan decision C13: kind and tab come from the current step, not from the request.
        JpathCheckKind kind = step.checkKind();
        JpathTab tab = step.tab();

        JpathCheckSet previous = null;
        if (step.checkToken() != null) {
            previous = checks.findByTokenAndUserId(step.checkToken(), userId).orElse(null);
        } else {
            previous = checks.findFirstByUserIdAndSessionIdAndKindAndStatus(userId, session.getId(), kind, ISSUED)
                    .filter(c -> c.getTab() == tab).orElse(null);
        }
        if (previous != null && (ISSUED.equals(previous.getStatus())
                || (SUBMITTED.equals(previous.getStatus()) && !Boolean.FALSE.equals(previous.getPassed())))) {
            if (step.checkToken() == null) {
                q = new ArrayList<>(q);
                q.set(position, step.withToken(previous.getToken()));
                JpathCheckEffects.write(session, q);
            }
            return view(previous);
        }

        String level = path.getOpenLevel();
        Integer attemptNo = previous == null ? 1 : previous.getAttemptNo() + 1;
        JpathUserSettings s = settings.findById(userId).orElse(null);
        boolean timerOn = switch (kind) {
            case KANA_ROW, GROUP_EXERCISE -> false;
            default -> r.timerOn() != null ? r.timerOn()
                    : tab == JpathTab.GRAMMAR ? (s == null || s.isGrammarTimerOn()) : (s != null && s.isQuizTimerOn());
        };
        long seed = SEEDS.nextLong();
        Built built = build(userId, kind, tab, level, q, position, step, previous, timerOn, seed);
        if (built.check().questions().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }

        String token = UUID.randomUUID().toString();
        JpathCheckIssueRequest echo = new JpathCheckIssueRequest(kind, tab, session.getId(), r.level(), r.topicKey(),
                r.unitNo(), r.source(), r.count(), r.direction(), r.timerOn(), r.itemCode(), r.redoOf());
        JpathCheckSet set = JpathCheckSet.builder()
                .token(token).userId(userId).planId(session.getPlanId()).sessionId(session.getId())
                .kind(kind).tab(tab).level(kind == JpathCheckKind.KANA_ROW ? null : level)
                .attemptNo(attemptNo).status(ISSUED).timerOn(timerOn)
                .issueJson(json(echo))
                .questionsJson(json(built.check().questions()))
                .answersJson(json(new StoredKeys(built.check().answers(), built.questionCodes())))
                .progressJson(json(new Progress(null, null)))
                .itemCodesJson(json(built.check().itemCodes()))
                .issuedAt(clock.now())
                .totalCount(units(built.check().questions(), built.check().answers()))
                .build();
        checks.save(set);
        q = new ArrayList<>(q);
        q.set(position, step.withToken(token));
        JpathCheckEffects.write(session, q);
        return view(set);
    }

    private record Built(BuiltCheck check, Map<String, String> questionCodes) {
    }

    private Built build(Long userId, JpathCheckKind kind, JpathTab tab, String level, List<JpathQueueStep> q,
                        int position, JpathQueueStep step, JpathCheckSet previous, boolean timerOn, long seed) {
        switch (kind) {
            case KANA_ROW -> {
                List<JpathKana> row = content.kana(null).stream()
                        .filter(k -> step.itemCode().equals(k.getRowCode())).toList();
                return new Built(builder.kanaRowCheck(row, seed), Map.of());
            }
            case GROUP_EXERCISE -> {
                List<String> recent = new ArrayList<>();
                for (int i = position - 1; i >= 0 && recent.size() < GROUP_WORDS; i--) {
                    JpathQueueStep st = q.get(i);
                    if (isNew(st, JpathTab.VOCAB) && !recent.contains(st.itemCode())) {
                        recent.add(0, st.itemCode());
                    }
                }
                List<JpathVocabulary> words = content.vocabs(recent);
                return new Built(builder.groupExercise(words, vocabPool(userId, q, level, words), seed), Map.of());
            }
            default -> {
                JpathItemType type = JpathItemType.valueOf(tab.name());
                List<String> codes = new ArrayList<>();
                for (JpathQueueStep st : q) {
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
                        yield new Built(builder.vocabCheck(words, vocabPool(userId, q, level, words), timerOn, seed),
                                Map.of());
                    }
                    case KANJI -> {
                        List<JpathKanji> kanji = codes.stream().map(content::kanji).flatMap(Optional::stream).toList();
                        yield new Built(builder.kanjiCheck(kanji, kanjiPool(userId, level, kanji), timerOn, seed),
                                Map.of());
                    }
                    default -> grammarBuilt(codes, previous, timerOn, seed);
                };
            }
        }
    }

    private Built grammarBuilt(List<String> codes, JpathCheckSet previous, boolean timerOn, long seed) {
        List<JpathGrammar> patterns = codes.stream().map(content::grammar).flatMap(Optional::stream).toList();
        Map<String, List<JpathGrammarQuestion>> bank = new LinkedHashMap<>();
        for (JpathGrammar g : patterns) {
            bank.put(g.getCode(), content.questions(g.getCode(), JpathContentReader.CHECK_BANK));
        }
        Set<String> used = previous == null ? Set.of()
                : new HashSet<>(readKeys(previous).questionCodes().values());
        BuiltCheck check = builder.grammarCheck(patterns, bank, used, timerOn, seed);
        // The builder's public view has no bank code: find each question's row by pattern + prompt.
        Map<String, String> questionCodes = new LinkedHashMap<>();
        for (JpathQuestionView v : check.questions()) {
            AnswerKey key = check.answers().get(v.id());
            if (key == null || key.itemCode() == null) {
                continue;
            }
            Set<String> taken = new HashSet<>(questionCodes.values());
            bank.getOrDefault(key.itemCode(), List.of()).stream()
                    .filter(row -> Objects.equals(row.getPromptJa(), v.prompt()) && !taken.contains(row.getCode()))
                    .findFirst()
                    .ifPresent(row -> questionCodes.put(v.id(), row.getCode()));
        }
        return new Built(check, questionCodes);
    }

    private static boolean isNew(JpathQueueStep st, JpathTab tab) {
        return st.kind() == JpathStepKind.NEW && !st.repeat() && st.tab() == tab && st.itemCode() != null;
    }

    /**
     * "Orphans": items learned before today in the open level that no passed check has counted yet (LEARNING,
     * counted_at null), oldest first, at most {@link #MAX_ORPHANS}.
     */
    private List<String> orphans(Long userId, JpathItemType type, String level) {
        LocalDateTime startOfToday = clock.today(userId).atStartOfDay();
        return userItems.findByUserIdAndItemType(userId, type).stream()
                .filter(i -> i.getState() == JpathItemState.LEARNING && i.getCountedAt() == null
                        && level.equals(i.getLevel()) && i.getFirstLearnedAt() != null
                        && i.getFirstLearnedAt().isBefore(startOfToday))
                .sorted(Comparator.comparing(JpathUserItem::getFirstLearnedAt).thenComparing(JpathUserItem::getId))
                .limit(MAX_ORPHANS)
                .map(JpathUserItem::getItemCode)
                .toList();
    }

    private DistractorPool vocabPool(Long userId, List<JpathQueueStep> q, String level, List<JpathVocabulary> asked) {
        List<String> sessionCodes = q.stream().filter(st -> isNew(st, JpathTab.VOCAB)).map(JpathQueueStep::itemCode)
                .distinct().toList();
        List<JpathVocabulary> session = content.vocabs(sessionCodes);
        List<JpathVocabulary> same = content.visibleVocabulary(level).stream().filter(content::learnable).toList();
        return new DistractorPool(session, same, confusables(userId, JpathItemType.VOCAB,
                asked.stream().map(JpathVocabulary::getCode).toList()));
    }

    private DistractorPool kanjiPool(Long userId, String level, List<JpathKanji> asked) {
        List<JpathKanji> same = content.visibleKanji(level).stream().filter(content::learnable).toList();
        return new DistractorPool(asked, same, confusables(userId, JpathItemType.KANJI,
                asked.stream().map(JpathKanji::getCode).toList()));
    }

    /** Confusable partners, only when the learner has studied the partner too. */
    private Map<String, String> confusables(Long userId, JpathItemType type, List<String> codes) {
        Set<String> studied = new HashSet<>(userItems.findCodesByUserIdAndItemType(userId, type));
        Map<String, String> out = new HashMap<>();
        for (String code : codes) {
            content.confusablePartner(type, code).filter(studied::contains).ifPresent(p -> out.put(code, p));
        }
        return out;
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
            if (a != null) {
                answered.add(new JpathAnsweredView(qv.id(), a.value(), a.correct(), correctValue(qv, key),
                        key.explanation(), null, 0, report(key, keys.questionCodes().get(qv.id()))));
            }
        }
        return new JpathCheckView(set.getToken(), set.getKind(), set.getTab(), set.getAttemptNo(), set.isTimerOn(),
                set.getStatus(), read(set.getIssueJson(), JpathCheckIssueRequest.class), questions, answered);
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
        set.setLastAnswerAt(clock.now());
        if (b.matched().size() == key.pairs().size() || b.matched().contains(r.leftId())) {
            return new JpathPairPickResult(b.matched().contains(r.leftId()), b.errors(),
                    b.matched().size() == key.pairs().size(), b.matched());
        }
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
        String questionCode = keys.questionCodes().get(qv.id());
        Progress p = readProgress(set);
        Answer done = p.answers().get(qv.id());
        if (done != null) {
            return result(qv, key, questionCode, done);
        }
        requireOpen(set);
        boolean timedOut = r.value() == null || r.value().isBlank()
                || (qv.timerSec() != null && r.elapsedMs() != null
                    && r.elapsedMs() > qv.timerSec() * 1000L + TIMER_GRACE_MS);
        boolean correct = !timedOut && correct(qv, key, r.value());
        Answer a = new Answer(r.value(), correct, r.elapsedMs());
        p.answers().put(qv.id(), a);
        set.setProgressJson(json(p));
        set.setLastAnswerAt(clock.now());
        if (key.itemCode() != null) {
            event(set, "c" + set.getId() + "-" + qv.id(), key.itemType(), key.itemCode(), correct, r.elapsedMs());
        }
        return result(qv, key, questionCode, a);
    }

    private JpathAnswerResult result(JpathQuestionView qv, AnswerKey key, String questionCode, Answer a) {
        return new JpathAnswerResult(a.correct(), correctValue(qv, key), key.explanation(),
                a.correct() ? null : compare(qv, key, a.value()), report(key, questionCode));
    }

    static boolean correct(JpathQuestionView qv, AnswerKey key, String value) {
        return switch (qv.type()) {
            case "ORDER" -> key.order() != null && String.join(",", key.order())
                    .equals(value.replaceAll("\\s+", ""));
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

    /** Side-by-side card when the picked option is the label of the answer's confusable partner. */
    private JpathCompareView compare(JpathQuestionView qv, AnswerKey key, String value) {
        if (value == null || qv.options() == null || key.itemCode() == null
                || (key.itemType() != JpathItemType.VOCAB && key.itemType() != JpathItemType.KANJI)) {
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
        if (!complete(questions, keys, p)) {
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
                correct += Math.max(0, pairs - b.errors());
                b.wrongLeft().forEach(l -> wrong.add(key.pairItems().get(l)));
                continue;
            }
            Answer a = p.answers().get(qv.id());
            total++;
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
        Boolean passed = set.getKind() == JpathCheckKind.GROUP_EXERCISE ? null
                : percent >= JpathCheckSizing.PASS_PERCENT;
        LocalDateTime last = set.getLastAnswerAt() != null ? set.getLastAnswerAt() : now;
        long spentMs = Duration.between(set.getIssuedAt(), last).toMillis();
        boolean tooFast = total > 0 && spentMs < (long) JpathCheckSizing.TOO_FAST_MS_PER_UNIT * total;

        List<String> itemCodes = readList(set.getItemCodesJson());
        JpathStudySession session = set.getSessionId() == null ? null
                : ownedSession(set.getSessionId(), userId).orElse(null);
        JpathDailyPlan today = null;
        List<JpathDailyTab> tabs = List.of();
        if (JpathCheckEffects.todayRound(session) && set.getPlanId() != null) {
            today = plans.findForUpdate(userId, clock.today(userId))
                    .filter(plan -> plan.getId().equals(set.getPlanId())).orElse(null);
            if (today != null) {
                tabs = dailyTabs.findForUpdateByPlanId(today.getId());
            }
        }
        JpathCheckEffects.Applied applied = effects.apply(set,
                new JpathCheckEffects.Scored(passed, tooFast, itemCodes, wrong, grammarRatings), session, today, tabs);

        boolean rewardEligible = !tooFast && passed != null && passed && today != null;
        JpathCheckResult result = new JpathCheckResult(set.getToken(), correct, total, percent, passed,
                rewardEligible, tooFast, wrongItems(JpathCheckEffects.itemType(set), wrong), applied.cpAwarded(),
                applied.next());
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
        return result;
    }

    private static JpathRating lower(JpathRating a, JpathRating b) {
        return JpathSrsPolicy.quality(a) <= JpathSrsPolicy.quality(b) ? a : b;
    }

    private static boolean complete(List<JpathQuestionView> questions, StoredKeys keys, Progress p) {
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
            } else if (!p.answers().containsKey(qv.id())) {
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

    /** The session {@code id} only when it belongs to {@code userId} (plan decision 43). */
    private Optional<JpathStudySession> ownedSession(Long id, Long userId) {
        return id == null ? Optional.empty() : sessions.findByIdAndUserId(id, userId);
    }

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

    private static List<JpathQuestionView> readQuestions(JpathCheckSet set) {
        return read(set.getQuestionsJson(), new TypeReference<List<JpathQuestionView>>() { });
    }

    private static StoredKeys readKeys(JpathCheckSet set) {
        return read(set.getAnswersJson(), StoredKeys.class);
    }

    private static Progress readProgress(JpathCheckSet set) {
        return set.getProgressJson() == null ? new Progress(null, null) : read(set.getProgressJson(), Progress.class);
    }

    private static List<String> readList(String json) {
        return json == null ? List.of() : read(json, new TypeReference<List<String>>() { });
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
