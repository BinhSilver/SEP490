package com.jlas.dictionary.service.impl;

import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.dictionary.entity.DictionarySearchHistory;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyDictionaryWordsService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int HISTORY_LIMIT = 500;
    private static final Set<String> LEVELS = Set.of("N1", "N2", "N3", "N4", "N5");

    private final JpathUserPathRepository paths;
    private final UserLearningSurveyRepository surveys;
    private final DictionarySearchHistoryRepository history;
    private final VocabularyEntryRepository vocabulary;
    private final VocabularyKanjiRepository vocabularyKanji;

    public DailyDictionaryWordsService(JpathUserPathRepository paths, UserLearningSurveyRepository surveys,
            DictionarySearchHistoryRepository history, VocabularyEntryRepository vocabulary,
            VocabularyKanjiRepository vocabularyKanji) {
        this.paths = paths;
        this.surveys = surveys;
        this.history = history;
        this.vocabulary = vocabulary;
        this.vocabularyKanji = vocabularyKanji;
    }

    public record Word(Long id, String expression, String reading, String meaning, String jlptLevel) { }
    public record Result(String source, String level, List<Word> words) { }

    @Transactional(readOnly = true)
    public Result dailyWords(Long userId) {
        return dailyWords(userId, LocalDate.now(VIETNAM));
    }

    Result dailyWords(Long userId, LocalDate today) {
        if (userId == null) return new Result("NONE", null, List.of());
        long seed = userId * 1_000_003L + today.toEpochDay();
        String level = resolveTargetLevel(userId);
        if (level != null) {
            List<Word> words = mapWords(vocabulary.findDailyWordsForLevel(level, seed).stream()
                    .filter(entry -> hasVietnameseMeaning(entry.getMeaning())).toList());
            return words.isEmpty() ? new Result("NONE", null, List.of()) : new Result("TARGET_LEVEL", level, words);
        }

        List<DictionarySearchHistory> searches = history.findByUser_IdOrderBySearchedAtDescIdDesc(
                userId, PageRequest.of(0, HISTORY_LIMIT));
        List<DictionarySearchHistory> validSearches = new ArrayList<>();
        Set<String> terms = new LinkedHashSet<>();
        for (DictionarySearchHistory search : searches) {
            String query = search.getNormalizedQuery();
            if (isJapaneseOnly(query) && !"ROMAJI".equals(search.getSearchMode())
                    && !"VIETNAMESE".equals(search.getSearchMode())) {
                validSearches.add(search);
                terms.add(DictionaryUtil.normalizeReading(query));
            }
        }
        if (validSearches.isEmpty()) return new Result("NONE", null, List.of());

        Map<String, VocabularyEntry> byExpression = new LinkedHashMap<>();
        for (VocabularyEntry entry : vocabulary.findLookupByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
                terms, "PUBLISHED")) {
            byExpression.putIfAbsent(entry.getNormalizedExpression(), entry);
        }
        Map<String, VocabularyEntry> byReading = new LinkedHashMap<>();
        for (VocabularyEntry entry : vocabulary.findLookupByNormalizedReadingInAndDictionaryStatusOrderByIdAsc(terms)) {
            byReading.putIfAbsent(entry.getNormalizedReading(), entry);
        }
        Set<Long> selectedIds = validSearches.stream().map(DictionarySearchHistory::getSelectedVocabulary)
                .filter(entry -> entry != null && entry.getId() != null).map(VocabularyEntry::getId)
                .collect(Collectors.toSet());
        Map<Long, VocabularyEntry> selected = selectedIds.isEmpty() ? Map.of() : vocabulary.findByIdIn(
                new ArrayList<>(selectedIds)).stream().collect(Collectors.toMap(VocabularyEntry::getId, entry -> entry));

        Map<Long, VocabularyEntry> unique = new LinkedHashMap<>();
        for (DictionarySearchHistory search : validSearches) {
            String term = DictionaryUtil.normalizeReading(search.getNormalizedQuery());
            VocabularyEntry selectedEntry = search.getSelectedVocabulary();
            VocabularyEntry entry = selectedEntry == null ? null : selected.get(selectedEntry.getId());
            if (!isVisible(entry)) {
                VocabularyEntry expression = byExpression.get(term);
                VocabularyEntry reading = byReading.get(term);
                entry = expression == null ? reading : reading == null || expression.getId() < reading.getId()
                        ? expression : reading;
            }
            if (isVisible(entry)) unique.putIfAbsent(entry.getId(), entry);
        }
        if (unique.isEmpty()) return new Result("NONE", null, List.of());
        List<VocabularyEntry> choices = new ArrayList<>(unique.values());
        Collections.shuffle(choices, new Random(seed));
        return new Result("HISTORY", null, mapWords(choices.stream().limit(5).toList()));
    }

    String resolveTargetLevel(Long userId) {
        String pathLevel = paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(
                userId, JpathPathStatus.ACTIVE).map(path -> normalizeLevel(path.getTargetLevel())).orElse(null);
        if (pathLevel != null) return pathLevel;
        return surveys.findById(userId).map(survey -> normalizeLevel(survey.getTargetLevel())).orElse(null);
    }

    static String normalizeLevel(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return LEVELS.contains(normalized) ? normalized : null;
    }

    static boolean isJapaneseOnly(String query) {
        return query != null && !query.isEmpty() && query.codePoints().allMatch(cp ->
                (cp >= 0x3041 && cp <= 0x309F) || (cp >= 0x30A1 && cp <= 0x30FF && cp != 0x30FB)
                || DictionaryUtil.isKanjiCodepoint(cp) || cp == 0x3005 || cp == 0x3006);
    }

    static boolean hasVietnameseMeaning(String meaning) {
        if (meaning == null) return false;
        String lower = meaning.toLowerCase(Locale.ROOT);
        return lower.chars().anyMatch(cp -> VocabularyEntryRepository.VIETNAMESE_MEANING_LETTERS.indexOf(cp) >= 0);
    }

    private static boolean isVisible(VocabularyEntry entry) {
        return entry != null && "PUBLISHED".equals(entry.getDictionaryStatus())
                && !"TEXTBOOK".equals(entry.getUsageScope()) && hasVietnameseMeaning(entry.getMeaning());
    }

    private List<Word> mapWords(List<VocabularyEntry> entries) {
        List<Long> missing = entries.stream().filter(entry -> entry.getJlptLevel() == null
                && entry.getExpression() != null && entry.getExpression().codePointCount(0, entry.getExpression().length()) == 1)
                .map(VocabularyEntry::getId).toList();
        Map<Long, String> kanjiLevels = new LinkedHashMap<>();
        if (!missing.isEmpty()) {
            vocabularyKanji.findCompositionByVocabularyIds(missing).forEach(link ->
                    kanjiLevels.putIfAbsent(link.getVocabularyEntry().getId(), link.getKanji().getJlptLevel()));
        }
        return entries.stream().map(entry -> map(entry, kanjiLevels.get(entry.getId()))).toList();
    }

    private Word map(VocabularyEntry entry, String kanjiLevel) {
        String meaning = entry.getMeaning();
        String shortMeaning = meaning == null ? "" : java.util.Arrays.stream(meaning.strip().split("[\\r\\n;；]"))
                .map(String::strip).filter(DailyDictionaryWordsService::hasVietnameseMeaning)
                .findFirst().orElse("");
        return new Word(entry.getId(), entry.getExpression(), entry.getReading(), shortMeaning,
                DictionaryMapper.vocabularyLevel(entry, kanjiLevel));
    }
}
