package com.jlas.jlptpath.service.impl;

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
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.service.LearningProfileSource;
import java.util.ArrayList;
import java.util.Comparator;
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
 * [JLAS-W0108] Wrong VOCABULARY / KANJI items of a submitted Smart Test → path notebook (source SMART_TEST).
 * A word maps by its dictionary entry id (smallest code when several), else by the normalized lexeme against
 * {@code normalized_expression} then {@code normalized_reading}, only when exactly one visible word matches. A kanji
 * lexeme must be one codepoint. Only visible, learnable content (catalog statuses, not report-hidden, level released,
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

    public JpathSmartTestImporterImpl(LearningProfileSource profiles, JpathVocabularyRepository vocabulary,
                                      JpathKanjiRepository kanji, JpathUserItemRepository items,
                                      JpathCatalogService catalog, JpathItemStateMachine stateMachine,
                                      JpathContentReader content) {
        this.profiles = profiles;
        this.vocabulary = vocabulary;
        this.kanji = kanji;
        this.items = items;
        this.catalog = catalog;
        this.stateMachine = stateMachine;
        this.content = content;
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
