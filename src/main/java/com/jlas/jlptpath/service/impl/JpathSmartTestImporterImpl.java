package com.jlas.jlptpath.service.impl;

import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import com.jlas.jlptpath.repository.JpathKanjiRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathSmartTestImporter;
import com.jlas.jlptpath.util.JpathGrammarKey;
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.service.LearningProfileSource;
import java.util.ArrayList;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Wrong VOCABULARY / KANJI / GRAMMAR items of a submitted Smart Test → path notebook (source SMART_TEST).
 * A word maps by its dictionary entry id (smallest code when several), else by the normalized lexeme against
 * {@code normalized_expression} then {@code normalized_reading}, only when exactly one visible word matches. A kanji
 * lexeme must be one codepoint. A grammar point (owner 20/11) maps by {@link JpathGrammarKey} of its pattern against the
 * pattern, family key and variants of the learnable main patterns: the question's own level first, else any released
 * level, only when exactly one pattern matches. Only visible, learnable content (catalog statuses, not report-hidden, level released,
 * {@link JpathContentReader#learnable}) counts;
 * items the learner already has a row for are left alone. A non-SUBMITTED attempt keeps the source's CONFLICT.
 */
@Service
public class JpathSmartTestImporterImpl implements JpathSmartTestImporter {

    private static final String UNMAPPED = ":UNMAPPED_QUESTION:";
    private static final List<String> VOCAB_PREFIXES = List.of("VOCAB:", "VOCABULARY:");
    private static final String KANJI_PREFIX = "KANJI:";

    private final LearningProfileSource profiles;
    private final JpathVocabularyRepository vocabulary;
    private final JpathKanjiRepository kanji;
    private final JpathUserItemRepository items;
    private final JpathCatalogService catalog;
    private final JpathItemStateMachine stateMachine;
    private final JpathContentReader content;
    private final GrammarPointRepository grammarPoints;
    private static final ObjectMapper JSON = new ObjectMapper();

    public JpathSmartTestImporterImpl(LearningProfileSource profiles, JpathVocabularyRepository vocabulary,
                                      JpathKanjiRepository kanji, JpathUserItemRepository items,
                                      JpathCatalogService catalog, JpathItemStateMachine stateMachine,
                                      JpathContentReader content, GrammarPointRepository grammarPoints) {
        this.profiles = profiles;
        this.vocabulary = vocabulary;
        this.kanji = kanji;
        this.items = items;
        this.catalog = catalog;
        this.stateMachine = stateMachine;
        this.content = content;
        this.grammarPoints = grammarPoints;
    }

    @Override
    @Transactional(readOnly = true)
    public int previewWrongItemCount(Long userId, Long attemptId) {
        return newItems(userId, attemptId).values().stream().mapToInt(Map::size).sum();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int importWrongItems(Long userId, Long attemptId) {
        int added = 0;
        for (Map.Entry<JpathItemType, Map<String, String>> byType : newItems(userId, attemptId).entrySet()) {
            for (Map.Entry<String, String> item : byType.getValue().entrySet()) {
                JpathUserItem row = stateMachine.ensureRow(userId, byType.getKey(), item.getKey(), item.getValue());
                stateMachine.toNotebook(row, JpathNotebookSource.SMART_TEST);
                added++;
            }
        }
        return added;
    }

    /** Item type → (code → level) of the mapped wrong items the learner has no row for yet. */
    private Map<JpathItemType, Map<String, String>> newItems(Long userId, Long attemptId) {
        Set<Long> entryIds = new LinkedHashSet<>();
        Set<String> vocabLexemes = new LinkedHashSet<>();
        Set<String> kanjiChars = new LinkedHashSet<>();
        List<VocabMiss> vocabMisses = new ArrayList<>();
        Map<Long, String> grammarMisses = new LinkedHashMap<>();
        for (WeaknessEvidence w : profiles.loadForUser(userId, attemptId).weaknesses()) {
            if (w.wrongCount() < 1 || w.canonicalId() == null || w.canonicalId().contains(UNMAPPED)) {
                continue;
            }
            if (WeaknessEvidence.SKILL_VOCABULARY.equals(w.skill())) {
                String lexeme = strip(w.canonicalId(), VOCAB_PREFIXES);
                if (lexeme == null) {
                    continue;
                }
                String normalized = JpathKanaText.normalize(lexeme);
                vocabMisses.add(new VocabMiss(w.vocabularyEntryId(), normalized));
                if (w.vocabularyEntryId() != null) {
                    entryIds.add(w.vocabularyEntryId());
                }
                vocabLexemes.add(normalized);
            } else if (WeaknessEvidence.SKILL_GRAMMAR.equals(w.skill())) {
                if (w.grammarPointId() != null) {
                    grammarMisses.putIfAbsent(w.grammarPointId(), w.jlptLevel());
                }
            } else if (WeaknessEvidence.SKILL_KANJI.equals(w.skill())) {
                String lexeme = strip(w.canonicalId(), List.of(KANJI_PREFIX));
                if (lexeme != null && lexeme.codePointCount(0, lexeme.length()) == 1) {
                    kanjiChars.add(lexeme);
                }
            }
        }

        Map<JpathItemType, Map<String, String>> out = new LinkedHashMap<>();
        out.put(JpathItemType.VOCAB, mapVocab(vocabMisses, entryIds, vocabLexemes));
        out.put(JpathItemType.KANJI, mapKanji(kanjiChars));
        out.put(JpathItemType.GRAMMAR, mapGrammar(grammarMisses));
        for (Map.Entry<JpathItemType, Map<String, String>> e : out.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            Set<String> existing = items.findByUserIdAndItemTypeAndItemCodeIn(userId, e.getKey(), e.getValue().keySet())
                    .stream().map(JpathUserItem::getItemCode).collect(Collectors.toSet());
            e.getValue().keySet().removeAll(existing);
        }
        return out;
    }

    private Map<String, String> mapVocab(List<VocabMiss> misses, Set<Long> entryIds, Set<String> lexemes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (misses.isEmpty()) {
            return out;
        }
        Map<Long, List<JpathVocabulary>> byEntry = entryIds.isEmpty() ? Map.of()
                : visible(vocabulary.findByDictionaryEntryIdIn(entryIds)).stream()
                        .collect(Collectors.groupingBy(JpathVocabulary::getDictionaryEntryId));
        Map<String, List<JpathVocabulary>> byExpression = visible(vocabulary.findByNormalizedExpressionIn(lexemes))
                .stream().collect(Collectors.groupingBy(JpathVocabulary::getNormalizedExpression));
        Map<String, List<JpathVocabulary>> byReading = visible(vocabulary.findByNormalizedReadingIn(lexemes))
                .stream().collect(Collectors.groupingBy(JpathVocabulary::getNormalizedReading));
        for (VocabMiss miss : misses) {
            JpathVocabulary match;
            List<JpathVocabulary> linked = miss.entryId() == null ? null : byEntry.get(miss.entryId());
            if (linked != null && !linked.isEmpty()) {
                match = linked.stream().min(Comparator.comparing(JpathVocabulary::getCode)).orElseThrow();
            } else {
                match = single(byExpression.get(miss.normalized()));
                if (match == null && !byExpression.containsKey(miss.normalized())) {
                    match = single(byReading.get(miss.normalized()));
                }
            }
            if (match != null) {
                out.put(match.getCode(), match.getLevel());
            }
        }
        return out;
    }

    private Map<String, String> mapKanji(Set<String> characters) {
        Map<String, String> out = new LinkedHashMap<>();
        if (characters.isEmpty()) {
            return out;
        }
        Map<String, List<JpathKanji>> byChar = kanji.findByCharacterIn(characters).stream()
                .filter(k -> visible(k.getStatus(), k.isReportHidden(), k.getLevel()) && content.learnable(k))
                .collect(Collectors.groupingBy(JpathKanji::getCharacter));
        for (String c : characters) {
            List<JpathKanji> found = byChar.get(c);
            if (found != null && found.size() == 1) {
                out.put(found.get(0).getCode(), found.get(0).getLevel());
            }
        }
        return out;
    }

    /** grammar point id → question level of the wrong points; code → level of the patterns they map onto. */
    private Map<String, String> mapGrammar(Map<Long, String> misses) {
        Map<String, String> out = new LinkedHashMap<>();
        if (misses.isEmpty()) {
            return out;
        }
        Map<Long, String> patterns = new HashMap<>();
        for (GrammarPoint point : grammarPoints.findAllById(misses.keySet())) {
            patterns.put(point.getId(), point.getPattern());
        }
        // key → learnable main patterns (all released levels) carrying that key
        Map<String, Map<String, JpathGrammar>> byKey = new HashMap<>();
        for (JpathLevelInfo info : catalog.levels()) {
            List<JpathGrammar> visible = content.visibleGrammar(info.level());
            if (visible.isEmpty()) {
                continue;
            }
            Map<String, Integer> checks = content.visibleCheckCounts(info.level());
            for (JpathGrammar g : visible) {
                if (!content.learnable(g, checks.getOrDefault(g.getCode(), 0))) {
                    continue;
                }
                for (String key : keys(g)) {
                    byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(g.getCode(), g);
                }
            }
        }
        for (Map.Entry<Long, String> miss : misses.entrySet()) {
            String key = JpathGrammarKey.of(patterns.get(miss.getKey()));
            Collection<JpathGrammar> found = key.isEmpty() ? List.of() : byKey.getOrDefault(key, Map.of()).values();
            List<JpathGrammar> sameLevel = found.stream().filter(g -> g.getLevel().equals(miss.getValue())).toList();
            Collection<JpathGrammar> pick = sameLevel.isEmpty() ? found : sameLevel;
            if (pick.size() == 1) {
                JpathGrammar g = pick.iterator().next();
                out.put(g.getCode(), g.getLevel());
            }
        }
        return out;
    }

    private static Set<String> keys(JpathGrammar g) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(JpathGrammarKey.of(g.getPattern()));
        keys.add(JpathGrammarKey.of(g.getFamilyKey()));
        if (g.getPatternVariantsJson() != null && !g.getPatternVariantsJson().isBlank()) {
            try {
                for (String variant : JSON.readValue(g.getPatternVariantsJson(), new TypeReference<List<String>>() { })) {
                    keys.add(JpathGrammarKey.of(variant));
                }
            } catch (Exception ignored) {
                // malformed variants: match on pattern and family key only
            }
        }
        keys.remove("");
        return keys;
    }

    private List<JpathVocabulary> visible(List<JpathVocabulary> rows) {
        return rows.stream().filter(v -> visible(v.getStatus(), v.isReportHidden(), v.getLevel()) && content.learnable(v))
                .toList();
    }

    private boolean visible(JpathContentStatus status, boolean reportHidden, String level) {
        return !reportHidden && catalog.visibleStatuses().contains(status) && catalog.isReleased(level);
    }

    /** The one row of {@code rows}, or null when there is none or more than one distinct word. */
    private static JpathVocabulary single(List<JpathVocabulary> rows) {
        if (rows == null) {
            return null;
        }
        Map<String, JpathVocabulary> distinct = rows.stream()
                .collect(Collectors.toMap(JpathVocabulary::getCode, Function.identity(), (a, b) -> a));
        return distinct.size() == 1 ? distinct.values().iterator().next() : null;
    }

    private static String strip(String canonicalId, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (canonicalId.startsWith(prefix)) {
                String lexeme = canonicalId.substring(prefix.length()).trim();
                return lexeme.isEmpty() ? null : lexeme;
            }
        }
        return null;
    }

    private record VocabMiss(Long entryId, String normalized) {
    }
}
