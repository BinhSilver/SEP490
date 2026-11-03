package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.jlptpath.entity.JpathConfusablePair;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathConfusablePairRepository;
import com.jlas.jlptpath.repository.JpathExampleRepository;
import com.jlas.jlptpath.repository.JpathGrammarQuestionRepository;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import com.jlas.jlptpath.repository.JpathKanaRepository;
import com.jlas.jlptpath.repository.JpathKanjiRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.util.JpathKanaText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] The one reader of path content for learner-facing code. Visibility rule: status in
 * {@code catalog.visibleStatuses()}, not report-hidden, level released — anything else reads as absent, and an
 * unreleased level is empty. "Learnable" = visible and complete enough to teach: a word needs a meaning, a kanji
 * needs meanings and at least one level reading, a pattern needs a meaning and at least two visible CHECK questions.
 * Learnable counts are the progress denominators.
 */
@Component
@Transactional(readOnly = true)
public class JpathContentReader {

    /** Bank of the micro-check questions; a pattern needs {@link #MIN_CHECKS} visible ones to be learnable. */
    public static final String CHECK_BANK = "CHECK";
    public static final int MIN_CHECKS = 2;

    private static final List<String> SCRIPTS = List.of("HIRAGANA", "KATAKANA");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathCatalogService catalog;
    private final JpathVocabularyRepository vocabularies;
    private final JpathKanjiRepository kanjis;
    private final JpathGrammarRepository grammars;
    private final JpathGrammarQuestionRepository questions;
    private final JpathExampleRepository examples;
    private final JpathKanaRepository kanas;
    private final JpathUserItemRepository userItems;
    private final JpathConfusablePairRepository confusables;

    public JpathContentReader(JpathCatalogService catalog, JpathVocabularyRepository vocabularies,
                              JpathKanjiRepository kanjis, JpathGrammarRepository grammars,
                              JpathGrammarQuestionRepository questions, JpathExampleRepository examples,
                              JpathKanaRepository kanas, JpathUserItemRepository userItems,
                              JpathConfusablePairRepository confusables) {
        this.catalog = catalog;
        this.vocabularies = vocabularies;
        this.kanjis = kanjis;
        this.grammars = grammars;
        this.questions = questions;
        this.examples = examples;
        this.kanas = kanas;
        this.userItems = userItems;
        this.confusables = confusables;
    }

    // ---- visible lists -------------------------------------------------------------------------------------

    /** Visible words of {@code level} by sort order; empty when the level is not released. */
    public List<JpathVocabulary> visibleVocabulary(String level) {
        if (!catalog.isReleased(level)) {
            return List.of();
        }
        return vocabularies.findByLevelAndStatusInAndReportHiddenFalseOrderBySortOrder(level, catalog.visibleStatuses());
    }

    /** Visible kanji of {@code level} by sort order; empty when the level is not released. */
    public List<JpathKanji> visibleKanji(String level) {
        if (!catalog.isReleased(level)) {
            return List.of();
        }
        return kanjis.findByLevelAndStatusInAndReportHiddenFalseOrderBySortOrder(level, catalog.visibleStatuses());
    }

    /** Visible patterns of {@code level} by sort order; empty when the level is not released. */
    public List<JpathGrammar> visibleGrammar(String level) {
        if (!catalog.isReleased(level)) {
            return List.of();
        }
        return grammars.findByLevelAndStatusInAndReportHiddenFalseOrderBySortOrder(level, catalog.visibleStatuses());
    }

    /** Kana of {@code script} (HIRAGANA/KATAKANA, any case) in teaching order; null = Hiragana then Katakana. */
    public List<JpathKana> kana(String script) {
        if (script == null) {
            List<JpathKana> out = new ArrayList<>();
            SCRIPTS.forEach(s -> out.addAll(kanas.findByScriptOrderBySortOrder(s)));
            return out;
        }
        String s = script.trim().toUpperCase(Locale.ROOT);
        return SCRIPTS.contains(s) ? kanas.findByScriptOrderBySortOrder(s) : List.of();
    }

    // ---- learnable -----------------------------------------------------------------------------------------

    /** A word is learnable when it has a meaning. */
    public boolean learnable(JpathVocabulary v) {
        return v != null && hasText(v.getMeaningVi());
    }

    /** A kanji is learnable when it has meanings and at least one level reading. */
    public boolean learnable(JpathKanji k) {
        return k != null && hasText(k.getMeaningsVi()) && jsonArraySize(k.getLevelReadingsJson()) >= 1;
    }

    /** A pattern is learnable when it has a meaning and at least {@link #MIN_CHECKS} visible CHECK questions. */
    public boolean learnable(JpathGrammar g, int visibleCheckCount) {
        return g != null && hasText(g.getMeaningVi()) && visibleCheckCount >= MIN_CHECKS;
    }

    /** Pattern code → number of its visible CHECK questions, for the patterns of {@code level} that have any. */
    public Map<String, Integer> visibleCheckCounts(String level) {
        if (!catalog.isReleased(level)) {
            return Map.of();
        }
        Map<String, Integer> out = new HashMap<>();
        for (Object[] row : questions.countVisibleByGrammar(level, CHECK_BANK, catalog.visibleStatuses())) {
            out.put((String) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    /**
     * Number of visible learnable items of {@code type} in {@code level}: the denominator of every progress %.
     *
     * @throws IllegalArgumentException for KANA, which has no level (kana progress is counted by rows)
     */
    public int learnableCount(String level, JpathItemType type) {
        return learnableCandidates(level, type).size();
    }

    // ---- new-item queues -----------------------------------------------------------------------------------

    /**
     * Next new items of {@code type}: learnable, no learner row yet, accepted by {@code extraFilter}, ordered by
     * (priority, sort order) in priority mode or by sort order otherwise, then by code; at most {@code limit}.
     *
     * @throws IllegalArgumentException for KANA
     */
    public List<String> nextNewCodes(Long userId, String level, JpathItemType type, int limit, boolean priorityMode,
                                     Predicate<String> extraFilter) {
        if (limit <= 0) {
            return List.of();
        }
        List<Candidate> candidates = learnableCandidates(level, type);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> started = new HashSet<>(userItems.findCodesByUserIdAndItemType(userId, type));
        Comparator<Candidate> order = priorityMode
                ? Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::sortOrder)
                : Comparator.comparingInt(Candidate::sortOrder);
        return candidates.stream()
                .filter(c -> !started.contains(c.code()))
                .filter(c -> extraFilter == null || extraFilter.test(c.code()))
                .sorted(order.thenComparing(Candidate::code))
                .limit(limit)
                .map(Candidate::code)
                .toList();
    }

    /**
     * Next new kana-only words of {@code level} whose expression uses only {@code learnedKana} (as given by
     * {@link #learnedKana}): learnable, no learner row yet, by sort order then code; at most {@code limit}.
     */
    public List<String> nextKanaOnlyVocab(Long userId, String level, int limit, Set<String> learnedKana) {
        if (limit <= 0) {
            return List.of();
        }
        List<JpathVocabulary> visible = visibleVocabulary(level);
        if (visible.isEmpty()) {
            return List.of();
        }
        Set<String> started = new HashSet<>(userItems.findCodesByUserIdAndItemType(userId, JpathItemType.VOCAB));
        return visible.stream()
                .filter(JpathVocabulary::isKanaOnly)
                .filter(this::learnable)
                .filter(v -> !started.contains(v.getCode()))
                .filter(v -> JpathKanaText.coveredBy(v.getExpression(), learnedKana))
                .sorted(Comparator.comparingInt(JpathVocabulary::getSortOrder)
                        .thenComparing(JpathVocabulary::getCode))
                .limit(limit)
                .map(JpathVocabulary::getCode)
                .toList();
    }

    /** Kana the learner has counted (counted_at set), expanded with what they unlock ({@code expandLearned}). */
    public Set<String> learnedKana(Long userId) {
        List<String> codes = userItems.findByUserIdAndItemType(userId, JpathItemType.KANA).stream()
                .filter(i -> i.getCountedAt() != null)
                .map(JpathUserItem::getItemCode)
                .toList();
        if (codes.isEmpty()) {
            return JpathKanaText.expandLearned(List.of());
        }
        List<String> learned = kanas.findAllById(codes).stream().map(JpathKana::getKana).toList();
        return JpathKanaText.expandLearned(learned);
    }

    // ---- single items --------------------------------------------------------------------------------------

    /** The word {@code code} when visible. */
    public Optional<JpathVocabulary> vocab(String code) {
        return code == null ? Optional.empty() : vocabularies.findById(code)
                .filter(v -> visible(v.getStatus(), v.isReportHidden(), v.getLevel()));
    }

    /** The visible words among {@code codes}, in the order of {@code codes}; hidden and unknown codes are dropped. */
    public List<JpathVocabulary> vocabs(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, JpathVocabulary> byCode = new HashMap<>();
        vocabularies.findAllById(codes).stream()
                .filter(v -> visible(v.getStatus(), v.isReportHidden(), v.getLevel()))
                .forEach(v -> byCode.put(v.getCode(), v));
        return codes.stream().map(byCode::get).filter(Objects::nonNull).distinct().toList();
    }

    /** The kanji {@code code} when visible. */
    public Optional<JpathKanji> kanji(String code) {
        return code == null ? Optional.empty() : kanjis.findById(code).filter(this::visible);
    }

    /** The visible kanji among {@code codes}, in the order of {@code codes}; hidden and unknown codes are dropped. */
    public List<JpathKanji> kanjis(List<String> codes) {
        return inOrder(codes, kanjis::findAllById, JpathKanji::getCode, this::visible);
    }

    /** The visible patterns among {@code codes}, in the order of {@code codes}; hidden and unknown codes dropped. */
    public List<JpathGrammar> grammars(List<String> codes) {
        return inOrder(codes, grammars::findAllById, JpathGrammar::getCode,
                g -> visible(g.getStatus(), g.isReportHidden(), g.getLevel()));
    }

    /** The kana among {@code codes}, in the order of {@code codes}; unknown codes are dropped. */
    public List<JpathKana> kanaItems(List<String> codes) {
        return inOrder(codes, kanas::findAllById, JpathKana::getCode, k -> true);
    }

    /** The visible kanji written {@code character}. */
    public Optional<JpathKanji> kanjiByCharacter(String character) {
        return character == null ? Optional.empty() : kanjis.findByCharacter(character).filter(this::visible);
    }

    /** The visible kanji written as any of {@code characters}, keyed by character (one query; blanks ignored). */
    public Map<String, JpathKanji> kanjisByCharacter(Collection<String> characters) {
        List<String> wanted = characters == null ? List.of()
                : characters.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, JpathKanji> out = new HashMap<>();
        kanjis.findByCharacterIn(wanted).stream().filter(this::visible)
                .forEach(k -> out.putIfAbsent(k.getCharacter(), k));
        return out;
    }

    /** The pattern {@code code} when visible. */
    public Optional<JpathGrammar> grammar(String code) {
        return code == null ? Optional.empty() : grammars.findById(code)
                .filter(g -> visible(g.getStatus(), g.isReportHidden(), g.getLevel()));
    }

    /** The kana {@code code} (kana have no status or level, so they are always visible). */
    public Optional<JpathKana> kanaItem(String code) {
        return code == null ? Optional.empty() : kanas.findById(code);
    }

    /** Visible examples of an owner ({@code ownerType} VOCAB, KANJI or GRAMMAR, any case) by sort order. */
    public List<JpathExample> examples(String ownerType, String ownerCode) {
        JpathItemType type = parseType(ownerType);
        if (type == null || type == JpathItemType.KANA || ownerCode == null) {
            return List.of();
        }
        return examples.findByOwnerTypeAndOwnerCodeAndStatusInAndReportHiddenFalseOrderBySortOrder(
                        type, ownerCode, catalog.visibleStatuses()).stream()
                .filter(e -> catalog.isReleased(e.getLevel()))
                .toList();
    }

    /** Visible questions of a pattern in {@code bank} (CHECK, ORDER, FILL, EXTRA) by sort order. */
    public List<JpathGrammarQuestion> questions(String grammarCode, String bank) {
        if (grammarCode == null || bank == null) {
            return List.of();
        }
        return questions.findByGrammarCodeAndBankAndStatusInAndReportHiddenFalseOrderBySortOrder(
                        grammarCode, bank, catalog.visibleStatuses()).stream()
                .filter(q -> catalog.isReleased(q.getLevel()))
                .toList();
    }

    /**
     * The easily confused partner of a visible word or kanji: the other code of its first pair (by codes) whose
     * partner is visible and learnable. Empty for grammar and kana.
     */
    public Optional<String> confusablePartner(JpathItemType type, String code) {
        if (code == null || (type != JpathItemType.VOCAB && type != JpathItemType.KANJI)) {
            return Optional.empty();
        }
        Predicate<String> shown = type == JpathItemType.VOCAB
                ? c -> vocab(c).filter(this::learnable).isPresent()
                : c -> kanji(c).filter(this::learnable).isPresent();
        if (!(type == JpathItemType.VOCAB ? vocab(code).isPresent() : kanji(code).isPresent())) {
            return Optional.empty();
        }
        for (JpathConfusablePair p : confusables.findWithCode(type, code)) {
            String partner = code.equals(p.getCodeA()) ? p.getCodeB() : p.getCodeA();
            if (shown.test(partner)) {
                return Optional.of(partner);
            }
        }
        return Optional.empty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private record Candidate(String code, int sortOrder, int priority) {
    }

    private List<Candidate> learnableCandidates(String level, JpathItemType type) {
        if (type == null) {
            throw new IllegalArgumentException("Item type is required");
        }
        return switch (type) {
            case VOCAB -> candidates(visibleVocabulary(level).stream().filter(this::learnable).toList(),
                    JpathVocabulary::getCode, JpathVocabulary::getSortOrder, JpathVocabulary::getPriority);
            case KANJI -> candidates(visibleKanji(level).stream().filter(this::learnable).toList(),
                    JpathKanji::getCode, JpathKanji::getSortOrder, JpathKanji::getSortOrder);
            case GRAMMAR -> {
                List<JpathGrammar> visible = visibleGrammar(level);
                Map<String, Integer> checks = visible.isEmpty() ? Map.of() : visibleCheckCounts(level);
                yield candidates(visible.stream()
                                .filter(g -> learnable(g, checks.getOrDefault(g.getCode(), 0))).toList(),
                        JpathGrammar::getCode, JpathGrammar::getSortOrder, JpathGrammar::getPriority);
            }
            case KANA -> throw new IllegalArgumentException("Kana has no level queue");
        };
    }

    private static <T> List<Candidate> candidates(List<T> rows, Function<T, String> code,
                                                  ToIntFunction<T> sortOrder, ToIntFunction<T> priority) {
        return rows.stream().map(r -> new Candidate(code.apply(r), sortOrder.applyAsInt(r), priority.applyAsInt(r)))
                .toList();
    }

    private static <T> List<T> inOrder(List<String> codes, Function<List<String>, List<T>> load,
                                       Function<T, String> code, Predicate<T> keep) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, T> byCode = new HashMap<>();
        load.apply(codes).stream().filter(keep).forEach(r -> byCode.put(code.apply(r), r));
        return codes.stream().map(byCode::get).filter(Objects::nonNull).distinct().toList();
    }

    private boolean visible(JpathKanji k) {
        return visible(k.getStatus(), k.isReportHidden(), k.getLevel());
    }

    private boolean visible(JpathContentStatus status, boolean reportHidden, String level) {
        return !reportHidden && catalog.visibleStatuses().contains(status) && catalog.isReleased(level);
    }

    private static JpathItemType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return JpathItemType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static int jsonArraySize(String json) {
        if (!hasText(json)) {
            return 0;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
