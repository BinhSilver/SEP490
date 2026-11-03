package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCheckResult;
import com.jlas.jlptpath.dto.JpathWrongItemView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathConfusablePair;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathCheckSource;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathQuizDirection;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathConfusablePairRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.AnswerKey;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.BuiltCheck;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.DistractorPool;
import com.jlas.jlptpath.util.JpathQueueStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Chooses the items of a check and builds it (plan Task 38): skip one, topic check, free quiz, redo,
 * confusable drill, grammar practice / extra / review and the skill-day kana review; plus the distractor pools the
 * session checks share. Reads only — the caller holds the path lock and stores the result.
 */
@Component
public class JpathCheckPicker {

    /** Free quiz sizes (#19); default {@link #FREE_QUIZ_DEFAULT}. */
    static final Set<Integer> FREE_QUIZ_SIZES = Set.of(5, 10, 20);
    static final int FREE_QUIZ_DEFAULT = 10;
    /** "Tôi biết chủ đề này": at most 10 questions (#19). */
    static final int TOPIC_MAX = 10;
    static final int EXTRA_QUESTIONS = 3;
    static final int CONFUSABLE_MAX = JpathCheckBuilder.CONFUSABLE_MAX;
    static final String ORDER_BANK = "ORDER";
    static final String FILL_BANK = "FILL";
    static final String EXTRA_BANK = "EXTRA";
    private static final int REVIEW_PAGE = 200;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A built check plus the topic items that were not asked ({@code pending}, TOPIC_KNOWN only; server-side). */
    public record Built(BuiltCheck check, List<String> pending) {
        public Built {
            pending = pending == null ? List.of() : List.copyOf(pending);
        }

        static Built of(BuiltCheck check) {
            return new Built(check, List.of());
        }
    }

    private final JpathContentReader content;
    private final JpathCheckBuilder builder;
    private final JpathUserItemRepository userItems;
    private final JpathCheckSetRepository checks;
    private final JpathConfusablePairRepository pairs;
    private final JpathClock clock;

    public JpathCheckPicker(JpathContentReader content, JpathCheckBuilder builder, JpathUserItemRepository userItems,
                            JpathCheckSetRepository checks, JpathConfusablePairRepository pairs, JpathClock clock) {
        this.content = content;
        this.builder = builder;
        this.userItems = userItems;
        this.checks = checks;
        this.pairs = pairs;
        this.clock = clock;
    }

    // ---- skip one ------------------------------------------------------------------------------------------

    /** Level of a visible V/K/G item; JPATH_ITEM_NOT_FOUND when hidden or unknown. */
    String itemLevel(JpathItemType type, String code) {
        return switch (type) {
            case VOCAB -> content.vocab(code).map(JpathVocabulary::getLevel)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
            case KANJI -> content.kanji(code).map(JpathKanji::getLevel)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
            case GRAMMAR -> content.grammar(code).map(JpathGrammar::getLevel)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
            case KANA -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chữ cái không có bài bỏ qua.");
        };
    }

    /** "Bỏ qua (kiểm 1 câu)": one MCQ — word JA_VI, kanji meaning, or one CHECK question of the pattern. */
    Built skipOne(Long userId, JpathItemType type, String code, boolean timerOn, long seed) {
        String level = itemLevel(type, code);
        BuiltCheck check = switch (type) {
            case VOCAB -> {
                List<JpathVocabulary> one = content.vocabs(List.of(code)).stream().filter(content::learnable)
                        .toList();
                yield builder.quiz(one, vocabPool(userId, List.of(), level, one), 1, JpathQuizDirection.JA_VI,
                        timerOn, seed);
            }
            case KANJI -> {
                List<JpathKanji> one = content.kanjis(List.of(code)).stream().filter(content::learnable).toList();
                yield builder.quiz(one, kanjiPool(userId, level, one), 1, JpathQuizDirection.JA_VI, timerOn, seed);
            }
            default -> grammar(content.grammars(List.of(code)), Set.of(), 1, timerOn, seed);
        };
        return Built.of(requireQuestions(check));
    }

    // ---- topic ---------------------------------------------------------------------------------------------

    /**
     * "Tôi biết chủ đề này": min(10, n) MCQs both ways over the learnable words of the topic that are not KNOWN;
     * the rest are {@code pending} (they become TOPIC_PEND rows when the check applies).
     */
    Built topic(Long userId, String level, String topicKey, boolean timerOn, long seed) {
        if (topicKey == null || topicKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu chủ đề.");
        }
        Set<String> known = knownCodes(userId, JpathItemType.VOCAB);
        List<JpathVocabulary> words = content.visibleVocabulary(level).stream()
                .filter(v -> topicKey.equals(v.getTopicKey()) && content.learnable(v) && !known.contains(v.getCode()))
                .toList();
        BuiltCheck check = requireQuestions(builder.quiz(words, vocabPool(userId, List.of(), level, words),
                Math.min(TOPIC_MAX, words.size()), JpathQuizDirection.MIXED, timerOn, seed));
        Set<String> asked = new HashSet<>(check.itemCodes());
        return new Built(check, words.stream().map(JpathVocabulary::getCode).filter(c -> !asked.contains(c))
                .toList());
    }

    // ---- free quiz / redo ----------------------------------------------------------------------------------

    /** count ∈ {5, 10, 20}; null = 10; anything else → VALIDATION_FAILED. */
    static int quizSize(Integer count) {
        if (count == null) {
            return FREE_QUIZ_DEFAULT;
        }
        if (!FREE_QUIZ_SIZES.contains(count)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số câu chỉ có thể là 5, 10 hoặc 20.");
        }
        return count;
    }

    /**
     * Free quiz on one tab. LEARNED = items with a learner row that is not KNOWN; NOTEBOOK = notebook rows (none
     * for grammar); ALL = every visible learnable item of {@code level}. {@code level} null (LEARNED/NOTEBOOK) =
     * every level; {@code topicKey} filters words, {@code unitNo} filters patterns.
     */
    Built freeQuiz(Long userId, JpathTab tab, String level, String openLevel, String topicKey, Integer unitNo,
                   JpathCheckSource source, int count, JpathQuizDirection direction, boolean timerOn, long seed) {
        JpathItemType type = sliderType(tab);
        JpathCheckSource src = source == null ? JpathCheckSource.LEARNED : source;
        String poolLevel = level == null ? openLevel : level;
        List<String> codes;
        if (src == JpathCheckSource.ALL) {
            codes = switch (type) {
                case VOCAB -> content.visibleVocabulary(poolLevel).stream().map(JpathVocabulary::getCode).toList();
                case KANJI -> content.visibleKanji(poolLevel).stream().map(JpathKanji::getCode).toList();
                default -> content.visibleGrammar(poolLevel).stream().map(JpathGrammar::getCode).toList();
            };
        } else {
            JpathItemState only = src == JpathCheckSource.NOTEBOOK ? JpathItemState.NOTEBOOK : null;
            codes = userItems.findByUserIdAndItemType(userId, type).stream()
                    .filter(i -> i.getState() != JpathItemState.KNOWN && (only == null || i.getState() == only))
                    .filter(i -> level == null || level.equals(i.getLevel()))
                    .sorted(java.util.Comparator.comparing(JpathUserItem::getId))
                    .map(JpathUserItem::getItemCode)
                    .toList();
        }
        return Built.of(requireQuestions(quizOn(userId, type, codes, poolLevel, topicKey, unitNo, count, direction,
                timerOn, seed)));
    }

    /**
     * "Làm lại": a free quiz of the old check's tab on its items that are still shown (none → JPATH_ITEM_NOT_FOUND).
     */
    Built redo(Long userId, JpathCheckSet old, String openLevel, int count, JpathQuizDirection direction,
               boolean timerOn, long seed) {
        JpathItemType type = sliderType(old.getTab());
        List<String> codes = readCodes(old.getItemCodesJson());
        BuiltCheck check = quizOn(userId, type, codes, old.getLevel() == null ? openLevel : old.getLevel(), null,
                null, count, direction, timerOn, seed);
        if (check.questions().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
        }
        return Built.of(check);
    }

    private BuiltCheck quizOn(Long userId, JpathItemType type, List<String> codes, String poolLevel,
                              String topicKey, Integer unitNo, int count, JpathQuizDirection direction,
                              boolean timerOn, long seed) {
        switch (type) {
            case VOCAB -> {
                List<JpathVocabulary> words = content.vocabs(codes).stream().filter(content::learnable)
                        .filter(v -> topicKey == null || topicKey.equals(v.getTopicKey())).toList();
                return builder.quiz(words, vocabPool(userId, List.of(), poolLevel, words), count, direction, timerOn,
                        seed);
            }
            case KANJI -> {
                List<JpathKanji> kanji = content.kanjis(codes).stream().filter(content::learnable).toList();
                return builder.quiz(kanji, kanjiPool(userId, poolLevel, kanji), count, direction, timerOn, seed);
            }
            default -> {
                List<JpathGrammar> patterns = new ArrayList<>(content.grammars(codes).stream()
                        .filter(g -> unitNo == null || unitNo.equals(g.getUnitNo())).toList());
                Collections.shuffle(patterns, new Random(seed));
                return grammar(patterns.subList(0, Math.min(count, patterns.size())), Set.of(), count, timerOn,
                        seed);
            }
        }
    }

    // ---- confusables ---------------------------------------------------------------------------------------

    /**
     * ≤ 10 VI_JA questions from pairs whose two items the learner has both studied; pairs with an item answered
     * wrong in an earlier confusable drill come first, the rest in seed order.
     */
    Built confusable(Long userId, JpathTab tab, String openLevel, long seed) {
        JpathItemType type = tab == JpathTab.VOCAB ? JpathItemType.VOCAB
                : tab == JpathTab.KANJI ? JpathItemType.KANJI : null;
        if (type == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Dễ nhầm chỉ có ở Từ vựng và Kanji.");
        }
        Set<String> studied = new HashSet<>(userItems.findCodesByUserIdAndItemType(userId, type));
        Set<String> missed = pastMistakes(userId);
        Random rnd = new Random(seed);
        List<String[]> candidates = new ArrayList<>();
        for (JpathConfusablePair p : pairs.findByItemTypeOrderByCodeAAscCodeBAsc(type)) {
            if (studied.contains(p.getCodeA()) && studied.contains(p.getCodeB())) {
                candidates.add(new String[] {p.getCodeA(), p.getCodeB()});
            }
        }
        Collections.shuffle(candidates, rnd);
        candidates.sort(java.util.Comparator.comparing(
                (String[] c) -> !(missed.contains(c[0]) || missed.contains(c[1]))));
        List<Object> answers = new ArrayList<>();
        List<Object> partners = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String[] c : candidates) {
            if (answers.size() >= CONFUSABLE_MAX) {
                break;
            }
            boolean flip = missed.contains(c[1]) && !missed.contains(c[0]) || (!missed.contains(c[0]) && rnd.nextBoolean());
            String a = flip ? c[1] : c[0];
            String b = flip ? c[0] : c[1];
            if (used.contains(a)) {
                continue;
            }
            Optional<?> ea = entity(type, a);
            Optional<?> eb = entity(type, b);
            if (ea.isPresent() && eb.isPresent()) {
                used.add(a);
                answers.add(ea.get());
                partners.add(eb.get());
            }
        }
        DistractorPool pool = type == JpathItemType.VOCAB
                ? new DistractorPool(partners, content.visibleVocabulary(openLevel).stream().filter(content::learnable)
                        .toList(), Map.of())
                : new DistractorPool(partners, content.visibleKanji(openLevel).stream().filter(content::learnable)
                        .toList(), Map.of());
        return Built.of(requireQuestions(builder.confusableQuiz(answers, partners, pool, seed)));
    }

    private Optional<?> entity(JpathItemType type, String code) {
        return type == JpathItemType.VOCAB ? content.vocab(code).filter(content::learnable)
                : content.kanji(code).filter(content::learnable);
    }

    /** Items answered wrong in the learner's latest confusable drills. */
    private Set<String> pastMistakes(Long userId) {
        Set<String> out = new HashSet<>();
        for (JpathCheckSet c : checks.findTop10ByUserIdAndKindAndStatusOrderBySubmittedAtDescIdDesc(userId,
                JpathCheckKind.CONFUSABLE, JpathCheckServiceImpl.SUBMITTED)) {
            JpathCheckResult r = read(c.getResultJson(), JpathCheckResult.class);
            if (r != null && r.wrongItems() != null) {
                r.wrongItems().stream().map(JpathWrongItemView::code).filter(Objects::nonNull).forEach(out::add);
            }
        }
        return out;
    }

    // ---- grammar -------------------------------------------------------------------------------------------

    /** "Luyện ngay": 1 ORDER + 1 FILL_TEXT; a pattern without either → JPATH_CONTENT_NOT_LEARNABLE. */
    Built practice(String code, long seed) {
        JpathGrammar g = content.grammar(code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        return Built.of(requireQuestions(builder.grammarPractice(g, content.questions(code, ORDER_BANK),
                content.questions(code, FILL_BANK), seed)));
    }

    /** "Luyện thêm": 3 EXTRA-bank questions; fewer than 3 usable → JPATH_CONTENT_NOT_LEARNABLE. */
    Built extra(String code, boolean timerOn, long seed) {
        JpathGrammar g = content.grammar(code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        BuiltCheck check = builder.grammarQuestions(List.of(g), Map.of(code, content.questions(code, EXTRA_BANK)),
                Set.of(), EXTRA_QUESTIONS, timerOn, seed);
        if (check.questions().size() < EXTRA_QUESTIONS) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }
        return Built.of(check);
    }

    /**
     * Grammar review: one CHECK question per due pattern (not KNOWN, due now, at most {@code room}), avoiding the
     * questions of the learner's latest reviews (and of {@code previous}, the failed attempt of this step).
     */
    Built grammarReview(Long userId, int room, Set<String> previous, boolean timerOn, long seed) {
        List<String> due = room <= 0 ? List.of() : userItems.due(userId, JpathItemType.GRAMMAR,
                        clock.localNow(userId), PageRequest.of(0, REVIEW_PAGE)).stream()
                .map(JpathUserItem::getItemCode).toList();
        List<JpathGrammar> patterns = content.grammars(due).stream()
                .filter(g -> !content.questions(g.getCode(), JpathContentReader.CHECK_BANK).isEmpty())
                .limit(room).toList();
        Set<String> used = new HashSet<>(previous);
        for (JpathCheckSet c : checks.findTop10ByUserIdAndKindAndStatusOrderBySubmittedAtDescIdDesc(userId,
                JpathCheckKind.GRAMMAR_REVIEW, JpathCheckServiceImpl.SUBMITTED)) {
            used.addAll(questionCodes(c));
        }
        return Built.of(requireQuestions(grammar(patterns, used, patterns.size(), timerOn, seed)));
    }

    /** Bank questions (CHECK) of {@code patterns}, {@code count} slots. */
    BuiltCheck grammar(List<JpathGrammar> patterns, Set<String> used, int count, boolean timerOn, long seed) {
        Map<String, List<JpathGrammarQuestion>> bank = new LinkedHashMap<>();
        for (JpathGrammar g : patterns) {
            bank.put(g.getCode(), content.questions(g.getCode(), JpathContentReader.CHECK_BANK));
        }
        return builder.grammarQuestions(patterns, bank, used, count, timerOn, seed);
    }

    /** Bank question codes behind a stored check (answer keys, then the legacy map of older rows). */
    static Set<String> questionCodes(JpathCheckSet set) {
        Set<String> out = new LinkedHashSet<>();
        JpathCheckServiceImpl.StoredKeys keys = read(set.getAnswersJson(), JpathCheckServiceImpl.StoredKeys.class);
        if (keys == null) {
            return out;
        }
        keys.keys().values().stream().map(AnswerKey::questionCode).filter(Objects::nonNull).forEach(out::add);
        out.addAll(keys.questionCodes().values());
        return out;
    }

    // ---- kana ----------------------------------------------------------------------------------------------

    /** Skill-day kana review (#25): every kana of the rows whose kana are all counted, in teaching order. */
    Built skillKanaReview(Long userId, long seed) {
        Set<String> counted = new HashSet<>();
        userItems.findByUserIdAndItemType(userId, JpathItemType.KANA).stream()
                .filter(i -> i.getCountedAt() != null).forEach(i -> counted.add(i.getItemCode()));
        Map<String, List<JpathKana>> byRow = new LinkedHashMap<>();
        for (JpathKana k : content.kana(null)) {
            byRow.computeIfAbsent(k.getRowCode(), r -> new ArrayList<>()).add(k);
        }
        List<JpathKana> kana = new ArrayList<>();
        byRow.values().stream().filter(row -> row.stream().allMatch(k -> counted.contains(k.getCode())))
                .forEach(kana::addAll);
        return Built.of(requireQuestions(builder.kanaRowCheck(kana, seed)));
    }

    // ---- pools ---------------------------------------------------------------------------------------------

    DistractorPool vocabPool(Long userId, List<JpathQueueStep> q, String level, List<JpathVocabulary> asked) {
        List<String> sessionCodes = q.stream()
                .filter(st -> st.kind() == JpathStepKind.NEW && !st.repeat() && st.tab() == JpathTab.VOCAB
                        && st.itemCode() != null)
                .map(JpathQueueStep::itemCode).distinct().toList();
        List<JpathVocabulary> session = content.vocabs(sessionCodes);
        List<JpathVocabulary> same = content.visibleVocabulary(level).stream().filter(content::learnable).toList();
        return new DistractorPool(session, same, confusables(userId, JpathItemType.VOCAB,
                asked.stream().map(JpathVocabulary::getCode).toList()));
    }

    DistractorPool kanjiPool(Long userId, String level, List<JpathKanji> asked) {
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

    // ---- helpers -------------------------------------------------------------------------------------------

    private Set<String> knownCodes(Long userId, JpathItemType type) {
        Set<String> out = new HashSet<>();
        userItems.findByUserIdAndItemType(userId, type).stream().filter(i -> i.getState() == JpathItemState.KNOWN)
                .forEach(i -> out.add(i.getItemCode()));
        return out;
    }

    static JpathItemType sliderType(JpathTab tab) {
        return switch (tab == null ? JpathTab.SKILL : tab) {
            case VOCAB -> JpathItemType.VOCAB;
            case KANJI -> JpathItemType.KANJI;
            case GRAMMAR -> JpathItemType.GRAMMAR;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Bài này chỉ có ở Từ vựng, Kanji và Ngữ pháp.");
        };
    }

    private static BuiltCheck requireQuestions(BuiltCheck check) {
        if (check.questions().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }
        return check;
    }

    static List<String> readCodes(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, String[].class));
        } catch (Exception e) {
            throw new IllegalStateException("Broken check JSON", e);
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
}
