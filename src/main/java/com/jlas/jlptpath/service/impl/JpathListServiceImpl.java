package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathExampleView;
import com.jlas.jlptpath.dto.JpathFacet;
import com.jlas.jlptpath.dto.JpathGrammarFilter;
import com.jlas.jlptpath.dto.JpathGrammarLessonView;
import com.jlas.jlptpath.dto.JpathGrammarRowView;
import com.jlas.jlptpath.dto.JpathGrammarUnitView;
import com.jlas.jlptpath.dto.JpathKanaCellView;
import com.jlas.jlptpath.dto.JpathKanaRowView;
import com.jlas.jlptpath.dto.JpathKanjiFilter;
import com.jlas.jlptpath.dto.JpathKanjiRowView;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathTopicCardView;
import com.jlas.jlptpath.dto.JpathVocabFilter;
import com.jlas.jlptpath.dto.JpathVocabRowView;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarUnit;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanaRow;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabTopic;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathGrammarUnitRepository;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathVocabTopicRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathListService;
import com.jlas.jlptpath.util.JpathAudio;
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.jlptpath.util.JpathStrokeSvg;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] The JLPT path lists. Filters run in memory over the visible content of one level (read through
 * {@link JpathContentReader}) joined with one map of the learner's rows per item type, so a list costs a few queries
 * whatever its size. Rows without a learner row read as NEW. Topic and unit totals count learnable items only (the
 * progress denominator, {@code JpathContentReader.learnable}); the lists still show every visible row.
 */
@Service
@Transactional(readOnly = true)
public class JpathListServiceImpl implements JpathListService {

    private static final Logger log = LoggerFactory.getLogger(JpathListServiceImpl.class);
    private static final String NEW = "NEW";
    private static final String ALL = "ALL";
    private static final List<String> STATES = List.of(NEW, "LEARNING", "NOTEBOOK", "KNOWN");
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final List<String> SCRIPTS = List.of("HIRAGANA", "KATAKANA");
    private static final String ORDER_BANK = "ORDER";
    private static final String FILL_BANK = "FILL";
    private static final String EXTRA_BANK = "EXTRA";
    private static final int MIN_EXTRA = 3;
    /** ASCII queries shorter than this match by prefix (see {@link TextMatch}). */
    private static final int SHORT_QUERY = 3;
    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathCatalogService catalog;
    private final JpathContentReader content;
    private final JpathItemStateMachine states;
    private final JpathUserItemRepository items;
    private final JpathVocabTopicRepository topics;
    private final JpathGrammarUnitRepository units;
    private final JpathKanaRowRepository kanaRows;

    public JpathListServiceImpl(JpathCatalogService catalog, JpathContentReader content, JpathItemStateMachine states,
                                JpathUserItemRepository items, JpathVocabTopicRepository topics,
                                JpathGrammarUnitRepository units, JpathKanaRowRepository kanaRows) {
        this.catalog = catalog;
        this.content = content;
        this.states = states;
        this.items = items;
        this.topics = topics;
        this.units = units;
        this.kanaRows = kanaRows;
    }

    // ---- vocabulary ----------------------------------------------------------------------------------------

    @Override
    public List<JpathTopicCardView> topics(Long userId, String level) {
        String lv = releasedLevel(level);
        Map<String, List<JpathVocabulary>> byTopic = content.visibleVocabulary(lv).stream()
                .filter(content::learnable)
                .collect(Collectors.groupingBy(JpathVocabulary::getTopicKey, LinkedHashMap::new, Collectors.toList()));
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.VOCAB);
        List<JpathTopicCardView> out = new ArrayList<>();
        for (JpathVocabTopic t : topicsOf(lv)) {
            List<JpathVocabulary> words = byTopic.getOrDefault(t.getTopicKey(), List.of());
            if (words.isEmpty()) {
                continue;
            }
            int known = 0;
            int learning = 0;
            int notebook = 0;
            for (JpathVocabulary w : words) {
                switch (state(rows.get(w.getCode()))) {
                    case "KNOWN" -> known++;
                    case "LEARNING" -> learning++;
                    case "NOTEBOOK" -> notebook++;
                    default -> { }
                }
            }
            int total = words.size();
            out.add(new JpathTopicCardView(t.getTopicKey(), t.getTitleVi(), total, known, learning, notebook,
                    total - known - learning - notebook, known * 100 / total));
        }
        return out;
    }

    @Override
    public JpathPage<JpathVocabRowView> vocabulary(Long userId, JpathVocabFilter filter) {
        String lv = releasedLevel(filter.level());
        String wantedState = stateFilter(filter.state());
        String topic = blank(filter.topic()) ? null : filter.topic().trim();
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.VOCAB);
        Predicate<JpathVocabulary> query = vocabQuery(filter.q());
        List<JpathVocabulary> matched = content.visibleVocabulary(lv).stream().filter(query).toList();

        Predicate<JpathVocabulary> inTopic = v -> topic == null || topic.equals(v.getTopicKey());
        Predicate<JpathVocabulary> inState = v -> wantedState == null || wantedState.equals(state(rows.get(v.getCode())));

        Map<String, Long> topicCounts = matched.stream().filter(inState)
                .collect(Collectors.groupingBy(JpathVocabulary::getTopicKey, Collectors.counting()));
        Map<String, Long> stateCounts = matched.stream().filter(inTopic)
                .collect(Collectors.groupingBy(v -> state(rows.get(v.getCode())), Collectors.counting()));
        Map<String, List<JpathFacet>> facets = new LinkedHashMap<>();
        Map<String, Boolean> topicsWithWords = matched.stream()
                .collect(Collectors.toMap(JpathVocabulary::getTopicKey, v -> true, (a, b) -> a));
        facets.put("topics", topicsOf(lv).stream()
                .filter(t -> topicsWithWords.containsKey(t.getTopicKey()))
                .map(t -> new JpathFacet(t.getTopicKey(), t.getTitleVi(), topicCounts.getOrDefault(t.getTopicKey(), 0L)))
                .toList());
        facets.put("states", STATES.stream().map(s -> new JpathFacet(s, s, stateCounts.getOrDefault(s, 0L))).toList());

        List<JpathVocabRowView> all = matched.stream().filter(inTopic.and(inState))
                .map(v -> vocabRow(v, rows.get(v.getCode())))
                .toList();
        return page(all, filter.page(), facets);
    }

    private Predicate<JpathVocabulary> vocabQuery(String q) {
        if (blank(q)) {
            return v -> true;
        }
        String kana = JpathKanaText.normalize(q.trim());
        String romaji = q.trim().toLowerCase(Locale.ROOT);
        String fromRomaji = asciiLetters(romaji) ? JpathKanaText.romajiToHiragana(romaji) : null;
        String vi = JpathKanaText.foldVi(q);
        TextMatch m = TextMatch.of(q);
        return v -> m.text(JpathKanaText.normalize(v.getExpression()), kana)
                || m.text(JpathKanaText.normalize(v.getReading()), kana)
                || m.text(v.getNormalizedExpression(), kana)
                || m.text(v.getNormalizedReading(), kana)
                || (v.getRomaji() != null && m.text(v.getRomaji().toLowerCase(Locale.ROOT), romaji))
                || (fromRomaji != null && m.text(v.getNormalizedReading(), fromRomaji))
                || m.words(JpathKanaText.foldVi(v.getMeaningVi()), vi);
    }

    private JpathVocabRowView vocabRow(JpathVocabulary v, JpathUserItem row) {
        return new JpathVocabRowView(v.getCode(), JpathItemType.VOCAB, v.getExpression(), v.getReading(),
                v.getRomaji(), v.getMeaningVi(), v.getWordTypeLabel(), v.getTopicKey(), state(row), hard(row),
                content.learnable(v), v.getDictionaryEntryId(), JpathAudio.url(v.getAudioPath(), null));
    }

    // ---- kanji ---------------------------------------------------------------------------------------------

    @Override
    public JpathPage<JpathKanjiRowView> kanji(Long userId, JpathKanjiFilter filter) {
        String lv = releasedLevel(filter.level());
        Comparator<JpathKanji> order = kanjiOrder(filter.sort());
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.KANJI);
        List<JpathKanji> matched = content.visibleKanji(lv).stream().filter(kanjiQuery(filter.q())).toList();

        Map<Integer, String> radicalNames = new TreeMap<>();
        Map<Integer, Long> radicalCounts = new HashMap<>();
        for (JpathKanji k : matched) {
            if (k.getRadicalNumber() != null) {
                radicalNames.putIfAbsent(k.getRadicalNumber(), k.getRadicalNameVi());
                radicalCounts.merge(k.getRadicalNumber(), 1L, Long::sum);
            }
        }
        Map<String, List<JpathFacet>> facets = new LinkedHashMap<>();
        facets.put("radicals", radicalNames.entrySet().stream()
                .map(e -> new JpathFacet(String.valueOf(e.getKey()), e.getValue(), radicalCounts.get(e.getKey())))
                .toList());

        List<JpathKanjiRowView> all = matched.stream()
                .filter(k -> filter.radical() == null || filter.radical().equals(k.getRadicalNumber()))
                .sorted(order)
                .map(k -> kanjiRow(k, rows.get(k.getCode())))
                .toList();
        return page(all, filter.page(), facets);
    }

    private static Comparator<JpathKanji> kanjiOrder(String sort) {
        Comparator<JpathKanji> jlpt = Comparator.comparing(JpathKanji::getSortOrder,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(JpathKanji::getCode);
        String s = blank(sort) ? "JLPT" : sort.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "JLPT" -> jlpt;
            case "STROKES" -> Comparator.comparing(JpathKanji::getStrokeCount,
                    Comparator.nullsLast(Comparator.<Integer>naturalOrder())).thenComparing(jlpt);
            case "FREQUENCY" -> Comparator.comparing(JpathKanji::getFrequency,
                    Comparator.nullsLast(Comparator.<Integer>naturalOrder())).thenComparing(jlpt);
            case "HAN_VIET" -> Comparator.comparing((JpathKanji k) -> blank(k.getHanViet()) ? null
                            : JpathKanaText.foldVi(k.getHanViet()), Comparator.nullsLast(Comparator.<String>naturalOrder()))
                    .thenComparing(jlpt);
            default -> throw new ApiException(ErrorCode.BAD_REQUEST);
        };
    }

    private Predicate<JpathKanji> kanjiQuery(String q) {
        if (blank(q)) {
            return k -> true;
        }
        String raw = q.trim();
        String kana = JpathKanaText.normalize(raw);
        String lower = raw.toLowerCase(Locale.ROOT);
        String fromRomaji = asciiLetters(lower) ? JpathKanaText.romajiToHiragana(lower) : null;
        String vi = JpathKanaText.foldVi(raw);
        TextMatch m = TextMatch.of(raw);
        return k -> {
            if (contains(k.getCharacter(), raw) || m.words(JpathKanaText.foldVi(k.getHanViet()), vi)
                    || m.words(JpathKanaText.foldVi(k.getMeaningsVi()), vi)) {
                return true;
            }
            for (String json : new String[] {k.getReadingsOnJson(), k.getReadingsKunJson(), k.getLevelReadingsJson()}) {
                for (String r : strings(k.getCode(), json)) {
                    String reading = JpathKanaText.normalize(r);
                    if (m.text(reading, kana) || (fromRomaji != null && m.text(reading, fromRomaji))) {
                        return true;
                    }
                }
            }
            return false;
        };
    }

    private JpathKanjiRowView kanjiRow(JpathKanji k, JpathUserItem row) {
        return new JpathKanjiRowView(k.getCode(), k.getCharacter(), upper(k.getHanViet()), k.getMeaningsVi(),
                strings(k.getCode(), k.getReadingsOnJson()), strings(k.getCode(), k.getReadingsKunJson()),
                strings(k.getCode(), k.getLevelReadingsJson()), k.getStrokeCount(), k.getRadicalNumber(),
                k.getRadicalNameVi(), k.getFrequency(), state(row), content.learnable(k),
                JpathStrokeSvg.url(k.getCharacter().codePointAt(0)));
    }

    // ---- grammar -------------------------------------------------------------------------------------------

    @Override
    public List<JpathGrammarUnitView> grammar(Long userId, JpathGrammarFilter filter) {
        String lv = releasedLevel(filter.level());
        List<JpathGrammar> visible = content.visibleGrammar(lv);
        Map<String, Integer> checks = visible.isEmpty() ? Map.of() : content.visibleCheckCounts(lv);
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.GRAMMAR);
        Map<Integer, List<JpathGrammar>> byUnit = byUnit(visible);
        Predicate<JpathGrammar> query = grammarQuery(filter.q());
        boolean searching = !blank(filter.q());
        Map<Integer, String> titles = unitTitles(lv);

        List<JpathGrammarUnitView> out = new ArrayList<>();
        for (Map.Entry<Integer, List<JpathGrammar>> e : byUnit.entrySet()) {
            if (filter.unit() != null && !filter.unit().equals(e.getKey())) {
                continue;
            }
            List<JpathGrammar> patterns = e.getValue();
            List<JpathGrammarRowView> matching = patterns.stream().filter(query)
                    .map(g -> grammarRow(g, rows.get(g.getCode()), checks))
                    .toList();
            if (searching && matching.isEmpty()) {
                continue;
            }
            List<JpathGrammar> learnable = patterns.stream()
                    .filter(g -> content.learnable(g, checks.getOrDefault(g.getCode(), 0))).toList();
            int done = (int) learnable.stream().map(g -> rows.get(g.getCode()))
                    .filter(r -> r != null && r.getCountedAt() != null).count();
            out.add(new JpathGrammarUnitView(e.getKey(), titles.get(e.getKey()), learnable.size(), done, matching));
        }
        return out;
    }

    @Override
    public JpathGrammarLessonView grammarLesson(Long userId, String code) {
        JpathGrammar found = content.grammar(code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        // a secondary pattern has no lesson of its own: its main pattern's lesson shows it
        JpathGrammar g = found.getParentCode() == null ? found : content.grammar(found.getParentCode()).orElse(found);
        String lv = g.getLevel();
        List<JpathGrammar> visible = content.visibleGrammar(lv);
        Map<String, Integer> checks = content.visibleCheckCounts(lv);
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.GRAMMAR);

        List<JpathGrammar> unit = byUnit(visible).getOrDefault(g.getUnitNo(), List.of(g));
        int index = indexOf(unit, g.getCode());
        int levelIndex = indexOf(visible, g.getCode());
        String prev = levelIndex > 0 ? visible.get(levelIndex - 1).getCode() : null;
        String next = levelIndex >= 0 && levelIndex + 1 < visible.size() ? visible.get(levelIndex + 1).getCode() : null;

        Map<String, JpathGrammar> byCode = visible.stream()
                .collect(Collectors.toMap(JpathGrammar::getCode, Function.identity()));
        List<JpathGrammarRowView> related = strings(g.getCode(), g.getRelatedCodesJson()).stream()
                .filter(c -> !c.equals(g.getCode()))
                .distinct()
                .map(c -> Optional.ofNullable(byCode.get(c)).or(() -> content.grammar(c)))
                .flatMap(Optional::stream)
                .map(r -> grammarRow(r, rows.get(r.getCode()), r.getLevel().equals(lv) ? checks
                        : content.visibleCheckCounts(r.getLevel())))
                .toList();
        List<JpathExampleView> examples = content.examples(JpathItemType.GRAMMAR.name(), g.getCode()).stream()
                .map(e -> new JpathExampleView(e.getCode(), e.getRole(), e.getJa(), e.getJaFurigana(), e.getVi(),
                        JpathAudio.url(e.getAudioPath(), null)))
                .toList();
        boolean practice = !content.questions(g.getCode(), ORDER_BANK).isEmpty()
                && !content.questions(g.getCode(), FILL_BANK).isEmpty();
        boolean extra = content.questions(g.getCode(), EXTRA_BANK).size() >= MIN_EXTRA;
        JpathUserItem row = rows.get(g.getCode());

        return new JpathGrammarLessonView(g.getCode(), lv, g.getUnitNo(), unitTitles(lv).get(g.getUnitNo()),
                index + 1, unit.size(), g.getPattern(), strings(g.getCode(), g.getPatternVariantsJson()),
                g.getMeaningVi(), g.getFormation(), g.getUsageVi(), g.getNuanceVi(), g.getCommonMistakesVi(), related,
                examples, practice, extra, state(row), content.learnable(g, checks.getOrDefault(g.getCode(), 0)), prev,
                next, attached(g.getCode()));
    }

    /** The secondary patterns attached to {@code mainCode}, each with its first (MAIN) example. */
    private List<com.jlas.jlptpath.dto.JpathAttachedPatternView> attached(String mainCode) {
        return content.attachedGrammar(mainCode).stream().map(a -> {
            var ex = content.examples(JpathItemType.GRAMMAR.name(), a.getCode()).stream().findFirst().orElse(null);
            return new com.jlas.jlptpath.dto.JpathAttachedPatternView(a.getCode(), a.getPattern(), a.getMeaningVi(),
                    ex == null ? null : ex.getJa(), ex == null ? null : ex.getJaFurigana(), ex == null ? null : ex.getVi());
        }).toList();
    }

    private JpathGrammarRowView grammarRow(JpathGrammar g, JpathUserItem row, Map<String, Integer> checks) {
        return new JpathGrammarRowView(g.getCode(), g.getPattern(), g.getMeaningVi(), state(row),
                content.learnable(g, checks.getOrDefault(g.getCode(), 0)));
    }

    private static Predicate<JpathGrammar> grammarQuery(String q) {
        if (blank(q)) {
            return g -> true;
        }
        String kana = JpathKanaText.normalize(q.trim());
        String vi = JpathKanaText.foldVi(q);
        TextMatch m = TextMatch.of(q);
        return g -> m.text(JpathKanaText.normalize(g.getPattern()), kana)
                || m.words(JpathKanaText.foldVi(g.getMeaningVi()), vi);
    }

    /** Visible patterns grouped by unit number (ascending), each unit in the reader's teaching order. */
    private static Map<Integer, List<JpathGrammar>> byUnit(List<JpathGrammar> visible) {
        return visible.stream().collect(Collectors.groupingBy(JpathGrammar::getUnitNo, TreeMap::new,
                Collectors.toList()));
    }

    private Map<Integer, String> unitTitles(String level) {
        Map<Integer, String> out = new HashMap<>();
        for (JpathGrammarUnit u : units.findAll()) {
            if (level.equals(u.getLevel())) {
                out.put(u.getUnitNo(), u.getTitleVi());
            }
        }
        return out;
    }

    private static int indexOf(List<JpathGrammar> list, String code) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getCode().equals(code)) {
                return i;
            }
        }
        return -1;
    }

    // ---- kana ----------------------------------------------------------------------------------------------

    @Override
    public List<JpathKanaRowView> kana(Long userId, String script) {
        String s = null;
        if (script != null && !script.isBlank()) {
            s = script.trim().toUpperCase(Locale.ROOT);
            if (!SCRIPTS.contains(s)) {
                throw new ApiException(ErrorCode.BAD_REQUEST);
            }
        }
        Map<String, List<JpathKana>> byRow = content.kana(s).stream()
                .collect(Collectors.groupingBy(JpathKana::getRowCode, LinkedHashMap::new, Collectors.toList()));
        Map<String, JpathUserItem> rows = rows(userId, JpathItemType.KANA);
        String wanted = s;
        List<JpathKanaRow> kanaRowList = kanaRows.findAll(Sort.by("sortOrder", "code")).stream()
                .filter(r -> wanted == null || wanted.equals(r.getScript()))
                .sorted(Comparator.comparingInt((JpathKanaRow r) -> SCRIPTS.indexOf(r.getScript()))
                        .thenComparing(JpathKanaRow::getSortOrder).thenComparing(JpathKanaRow::getCode))
                .toList();
        List<JpathKanaRowView> out = new ArrayList<>();
        for (JpathKanaRow r : kanaRowList) {
            List<JpathKana> kanas = byRow.getOrDefault(r.getCode(), List.of());
            if (kanas.isEmpty()) {
                continue;
            }
            boolean passed = kanas.stream().allMatch(k -> {
                JpathUserItem i = rows.get(k.getCode());
                return i != null && i.getCountedAt() != null;
            });
            List<JpathKanaCellView> cells = kanas.stream()
                    .map(k -> kanaCell(k, rows.get(k.getCode())))
                    .toList();
            out.add(new JpathKanaRowView(r.getCode(), r.getTitleVi(), r.getRowKind(), passed, cells));
        }
        return out;
    }

    private static JpathKanaCellView kanaCell(JpathKana k, JpathUserItem row) {
        return new JpathKanaCellView(k.getCode(), k.getKana(), k.getRomaji(), state(row),
                JpathAudio.url(null, k.getKana()), JpathStrokeSvg.url(k.getKana().codePointAt(0)));
    }

    // ---- notebook / known ----------------------------------------------------------------------------------

    @Override
    public JpathPage<JpathVocabRowView> notebook(Long userId, String tab, int page) {
        JpathItemType type = tabType(tab);
        if (type != JpathItemType.VOCAB && type != JpathItemType.KANJI) {
            return page(List.of(), page, Map.of());
        }
        return store(userId, type, JpathItemState.NOTEBOOK, JpathUserItem::getNotebookSince, page);
    }

    @Override
    public JpathPage<JpathVocabRowView> known(Long userId, String tab, int page) {
        JpathItemType type = tabType(tab);
        if (type == JpathItemType.KANA) {
            return page(List.of(), page, Map.of());
        }
        return store(userId, type, JpathItemState.KNOWN, JpathUserItem::getKnownAt, page);
    }

    @Override
    public JpathPage<JpathKanaCellView> knownKana(Long userId, int page) {
        List<JpathUserItem> held = held(userId, JpathItemType.KANA, JpathItemState.KNOWN, JpathUserItem::getKnownAt);
        Map<String, JpathKana> byCode = content.kanaItems(codes(held)).stream()
                .collect(Collectors.toMap(JpathKana::getCode, Function.identity()));
        List<JpathKanaCellView> all = held.stream()
                .map(i -> Optional.ofNullable(byCode.get(i.getItemCode())).map(k -> kanaCell(k, i)).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        return page(all, page, Map.of());
    }

    /**
     * Rows of {@code type} in {@code state} whose item is visible, newest {@code since} first. Only the held codes
     * are looked up (through the reader's visible batch accessors).
     */
    private JpathPage<JpathVocabRowView> store(Long userId, JpathItemType type, JpathItemState state,
                                               Function<JpathUserItem, LocalDateTime> since, int page) {
        List<JpathUserItem> held = held(userId, type, state, since);
        if (held.isEmpty()) {
            return page(List.of(), page, Map.of());
        }
        List<String> codes = codes(held);
        Function<JpathUserItem, JpathVocabRowView> toRow = switch (type) {
            case VOCAB -> {
                Map<String, JpathVocabulary> byCode = content.vocabs(codes).stream()
                        .collect(Collectors.toMap(JpathVocabulary::getCode, Function.identity()));
                yield i -> Optional.ofNullable(byCode.get(i.getItemCode())).map(v -> vocabRow(v, i)).orElse(null);
            }
            case KANJI -> {
                Map<String, JpathKanji> byCode = content.kanjis(codes).stream()
                        .collect(Collectors.toMap(JpathKanji::getCode, Function.identity()));
                yield i -> Optional.ofNullable(byCode.get(i.getItemCode())).map(k -> kanjiStoreRow(k, i)).orElse(null);
            }
            case GRAMMAR -> {
                List<JpathGrammar> found = content.grammars(codes);
                Map<String, JpathGrammar> byCode = found.stream()
                        .collect(Collectors.toMap(JpathGrammar::getCode, Function.identity()));
                Map<String, Integer> checks = new HashMap<>();
                found.stream().map(JpathGrammar::getLevel).distinct()
                        .forEach(l -> checks.putAll(content.visibleCheckCounts(l)));
                yield i -> Optional.ofNullable(byCode.get(i.getItemCode())).map(g -> grammarStoreRow(g, i, checks))
                        .orElse(null);
            }
            case KANA -> i -> null;
        };
        List<JpathVocabRowView> all = held.stream().map(toRow).filter(Objects::nonNull).toList();
        return page(all, page, Map.of());
    }

    /** The learner's rows of {@code type} in {@code state}, newest {@code since} first (null last), then id desc. */
    private List<JpathUserItem> held(Long userId, JpathItemType type, JpathItemState state,
                                     Function<JpathUserItem, LocalDateTime> since) {
        return items.findByUserIdAndItemType(userId, type).stream()
                .filter(i -> i.getState() == state)
                .sorted(Comparator.comparing(since, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                        .thenComparing(JpathUserItem::getId, Comparator.reverseOrder()))
                .toList();
    }

    private static List<String> codes(List<JpathUserItem> held) {
        return held.stream().map(JpathUserItem::getItemCode).distinct().toList();
    }

    private JpathVocabRowView kanjiStoreRow(JpathKanji k, JpathUserItem row) {
        List<String> readings = strings(k.getCode(), k.getLevelReadingsJson());
        String reading = readings.isEmpty() ? null : JpathKanaText.kanjiReadingLabel(readings.get(0));
        return new JpathVocabRowView(k.getCode(), JpathItemType.KANJI, k.getCharacter(), reading, null,
                k.getMeaningsVi(), null, null, state(row), hard(row), content.learnable(k), null, null);
    }

    private JpathVocabRowView grammarStoreRow(JpathGrammar g, JpathUserItem row, Map<String, Integer> checks) {
        return new JpathVocabRowView(g.getCode(), JpathItemType.GRAMMAR, g.getPattern(), null, null, g.getMeaningVi(),
                null, null, state(row), hard(row), content.learnable(g, checks.getOrDefault(g.getCode(), 0)), null,
                null);
    }

    private static JpathItemType tabType(String tab) {
        if (blank(tab)) {
            return JpathItemType.VOCAB;
        }
        try {
            return JpathItemType.valueOf(tab.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Upper-cased level after checking it is N1..N5 ({@code BAD_REQUEST}) and released. */
    private String releasedLevel(String level) {
        String lv = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(lv)) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        if (!catalog.isReleased(lv)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
        return lv;
    }

    private List<JpathVocabTopic> topicsOf(String level) {
        return topics.findAll(Sort.by("sortOrder", "topicKey")).stream()
                .filter(t -> level.equals(t.getLevel()))
                .toList();
    }

    private Map<String, JpathUserItem> rows(Long userId, JpathItemType type) {
        return items.findByUserIdAndItemType(userId, type).stream()
                .collect(Collectors.toMap(JpathUserItem::getItemCode, Function.identity(), (a, b) -> a));
    }

    private static String state(JpathUserItem row) {
        return row == null ? NEW : row.getState().name();
    }

    private boolean hard(JpathUserItem row) {
        return row != null && states.hard(row);
    }

    private static String stateFilter(String state) {
        if (blank(state) || ALL.equalsIgnoreCase(state.trim())) {
            return null;
        }
        String s = state.trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(s)) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        return s;
    }

    private static <T> JpathPage<T> page(List<T> all, int page, Map<String, List<JpathFacet>> facets) {
        int p = Math.max(0, page);
        long from = (long) p * PAGE_SIZE;
        List<T> slice = from >= all.size() ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + PAGE_SIZE));
        return new JpathPage<>(List.copyOf(slice), p, PAGE_SIZE, all.size(), facets);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && !needle.isEmpty() && haystack.contains(needle);
    }

    /**
     * How a search term matches. An ASCII query shorter than {@link #SHORT_QUERY} letters would match almost every
     * row with "contains", so it matches by prefix instead: {@link #text} = starts with, {@link #words} = some word
     * starts with it. Other queries match anywhere.
     */
    private record TextMatch(boolean prefix) {

        static TextMatch of(String q) {
            String t = q == null ? "" : q.trim();
            return new TextMatch(t.length() < SHORT_QUERY && t.chars().allMatch(c -> c < 0x80));
        }

        boolean text(String haystack, String needle) {
            return prefix ? haystack != null && !needle.isEmpty() && haystack.startsWith(needle)
                    : contains(haystack, needle);
        }

        boolean words(String haystack, String needle) {
            if (!prefix) {
                return contains(haystack, needle);
            }
            if (haystack == null || needle.isEmpty()) {
                return false;
            }
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
                if (i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean asciiLetters(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || c == ' ' || c == '-' || c == '\'');
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(VI);
    }

    /** A JSON string array as a list; malformed JSON is logged (item code) and reads as empty. */
    private static List<String> strings(String code, String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(node.size());
            node.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            log.warn("Malformed JSON array in item {}: {}", code, e.getMessage());
            return List.of();
        }
    }
}
