package com.jlas.dictionary.service.impl;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.*;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Cài đặt {@link DictionaryService}.
 *
 * <p>Chiến lược ranking (mục 8 JLAS2-34): với mỗi search mode, thu thập vocabulary theo các
 * "tier" giảm dần độ ưu tiên (exact -&gt; prefix -&gt; contains/reading phụ), mỗi tier là MỘT
 * query có LIMIT, dừng sớm khi đã đủ số lượng cần cho trang hiện tại — không N+1, không load
 * toàn bộ bảng vào memory. Vocabulary chứa Kanji được tra qua bảng nối {@code vocabulary_kanji}
 * (index idx_vocabulary_kanji_prefix/idx_vocabulary_kanji_lookup), không dùng LIKE '%kanji%'.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DictionaryServiceImpl implements DictionaryService {

    private static final String PUBLISHED = "PUBLISHED";
    /** JMdict là multilingual; ưu tiên hiện tiếng Việt nếu có, sau đó tiếng Anh. */
    private static final List<String> PREFERRED_GLOSS_LANGUAGES = List.of("vie", "eng");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** Chặn trên tổng số candidate gộp từ mọi tier trong một lần search, tránh query quá nặng. */
    private static final int MAX_TOTAL_CANDIDATES = 200;
    private static final int KANJI_RESULT_LIMIT = 8;
    private static final int RELATED_VOCABULARY_LIMIT = 50;

    private final VocabularyEntryRepository vocabularyEntryRepository;
    private final VocabularyKanjiRepository vocabularyKanjiRepository;
    private final VocabularySenseRepository vocabularySenseRepository;
    private final VocabularyGlossRepository vocabularyGlossRepository;
    private final VocabularyReadingRepository vocabularyReadingRepository;
    private final KanjiRepository kanjiRepository;
    private final KanjiReadingRepository kanjiReadingRepository;
    private final DictionarySearchHistoryRepository searchHistoryRepository;
    private final SensePartOfSpeechRepository sensePartOfSpeechRepository;
    private final SenseDomainRepository senseDomainRepository;
    private final UserRepository userRepository;
    private final DictionaryMapper mapper;

    @Override
    public SearchResultResponse search(String query, int page, int size, Long userId) {
        String trimmed = query == null ? "" : query.trim();
        String normalizedQuery = DictionaryUtil.normalizeText(trimmed);
        if (normalizedQuery.isEmpty()) {
            return emptyResult(trimmed);
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int candidatesNeeded = Math.min((safePage + 1) * safeSize, MAX_TOTAL_CANDIDATES);

        DictionaryUtil.SearchMode mode = DictionaryUtil.classify(normalizedQuery);

        List<KanjiResponse> kanjiResults = findKanjiResults(normalizedQuery, mode);
        List<VocabularyEntry> candidates = collectVocabularyCandidates(normalizedQuery, mode, candidatesNeeded);

        int fromIndex = Math.min(safePage * safeSize, candidates.size());
        int toIndex = Math.min(fromIndex + safeSize, candidates.size());
        List<VocabularyResponse> pageContent = toVocabularyResponses(candidates.subList(fromIndex, toIndex));

        boolean last = candidates.size() < candidatesNeeded || candidates.size() >= MAX_TOTAL_CANDIDATES;
        PageResponse<VocabularyResponse> pageResponse = PageResponse.<VocabularyResponse>builder()
                .content(pageContent)
                .page(safePage)
                .size(safeSize)
                .totalElements(candidates.size())
                .last(last)
                .build();

        int total = kanjiResults.size() + candidates.size();

        if (safePage == 0) {
            logHistory(trimmed, normalizedQuery, mode, total, userId);
        }

        return SearchResultResponse.builder()
                .query(trimmed)
                .searchMode(mode.name())
                .kanjiResults(kanjiResults)
                .vocabularyResults(pageResponse)
                .total(total)
                .build();
    }

    @Override
    public VocabularyResponse getVocabularyDetail(Long vocabularyId) {
        VocabularyEntry entry = vocabularyEntryRepository.findById(vocabularyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng"));
        return toVocabularyResponses(List.of(entry)).get(0);
    }

    @Override
    public KanjiResponse getKanjiDetail(String character) {
        String normalized = DictionaryUtil.normalizeText(character);
        Kanji kanji = kanjiRepository.findByCharacter(normalized)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy Kanji"));

        List<KanjiReadingResponse> readings = groupReadings(List.of(kanji.getId()))
                .getOrDefault(kanji.getId(), List.of());
        List<VocabularyEntry> related = vocabularyKanjiRepository.findVocabularyByKanjiCharacter(
                normalized, PageRequest.of(0, RELATED_VOCABULARY_LIMIT));

        KanjiResponse response = mapper.toKanjiResponse(kanji, readings);
        response.setRelatedVocabulary(toVocabularyResponses(related));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHistoryResponse> getRecentHistory(Long userId) {
        if (userId == null) return List.of();
        return searchHistoryRepository.findTop20ByUser_IdOrderBySearchedAtDesc(userId)
                .stream().map(mapper::toHistoryResponse).toList();
    }

    // -------------------------------------------------------------------
    // Vocabulary candidate collection theo tier, per search mode
    // -------------------------------------------------------------------

    private List<VocabularyEntry> collectVocabularyCandidates(
            String normalizedQuery, DictionaryUtil.SearchMode mode, int limit) {
        LinkedHashMap<Long, VocabularyEntry> collected = new LinkedHashMap<>();
        switch (mode) {
            case KANJI -> collectKanjiModeCandidates(normalizedQuery, limit, collected);
            case HIRAGANA, KATAKANA -> collectReadingModeCandidates(normalizedQuery, limit, collected);
            case ROMAJI -> collectRomajiModeCandidates(normalizedQuery, limit, collected);
            case VIETNAMESE -> collectMeaningModeCandidates(normalizedQuery, limit, collected);
        }
        return new ArrayList<>(collected.values());
    }

    private void collectKanjiModeCandidates(
            String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        vocabularyEntryRepository.findFirstByNormalizedExpressionAndDictionaryStatus(query, PUBLISHED)
                .ifPresent(e -> collected.put(e.getId(), e));
        if (collected.size() >= limit) return;

        boolean singleKanjiLiteral = query.codePointCount(0, query.length()) == 1;
        if (singleKanjiLiteral) {
            addAll(collected, vocabularyKanjiRepository.findVocabularyStartingWithKanji(
                    query, PageRequest.of(0, remaining(collected, limit))));
            if (collected.size() >= limit) return;
            addAll(collected, vocabularyKanjiRepository.findVocabularyContainingKanjiNotFirst(
                    query, PageRequest.of(0, remaining(collected, limit))));
        } else {
            addAll(collected, vocabularyEntryRepository
                    .findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
                            query, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
            if (collected.size() >= limit) return;
            addAll(collected, vocabularyEntryRepository
                    .findByNormalizedExpressionContainingAndDictionaryStatusOrderByIdAsc(
                            query, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
        }
    }

    private void collectReadingModeCandidates(
            String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        String readingKey = DictionaryUtil.normalizeReading(query);
        vocabularyEntryRepository.findFirstByNormalizedReadingAndDictionaryStatus(readingKey, PUBLISHED)
                .ifPresent(e -> collected.put(e.getId(), e));
        if (collected.size() >= limit) return;

        addAll(collected, vocabularyEntryRepository
                .findByNormalizedReadingStartingWithAndDictionaryStatusOrderByIdAsc(
                        readingKey, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
        if (collected.size() >= limit) return;

        addAll(collected, vocabularyReadingRepository.findVocabularyByNormalizedReading(
                readingKey, PageRequest.of(0, remaining(collected, limit))));
    }

    private void collectRomajiModeCandidates(
            String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        String romajiKey = query.toLowerCase(Locale.ROOT);
        vocabularyEntryRepository.findFirstByReadingRomajiIgnoreCaseAndDictionaryStatus(romajiKey, PUBLISHED)
                .ifPresent(e -> collected.put(e.getId(), e));
        if (collected.size() >= limit) return;

        addAll(collected, vocabularyEntryRepository
                .findByReadingRomajiStartingWithIgnoreCaseAndDictionaryStatusOrderByIdAsc(
                        romajiKey, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
    }

    private void collectMeaningModeCandidates(
            String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        addAll(collected, vocabularyEntryRepository.findByMeaningContainingAndDictionaryStatusOrderByIdAsc(
                query, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
        if (collected.size() >= limit) return;

        List<Long> glossVocabularyIds = vocabularyGlossRepository.findVocabularyIdsByGlossContaining(
                query, PageRequest.of(0, remaining(collected, limit)));
        if (!glossVocabularyIds.isEmpty()) {
            addAll(collected, vocabularyEntryRepository.findByIdIn(glossVocabularyIds));
        }
    }

    private static void addAll(LinkedHashMap<Long, VocabularyEntry> collected, List<VocabularyEntry> entries) {
        for (VocabularyEntry entry : entries) {
            collected.putIfAbsent(entry.getId(), entry);
        }
    }

    private static int remaining(LinkedHashMap<Long, VocabularyEntry> collected, int limit) {
        return Math.max(0, limit - collected.size());
    }

    // -------------------------------------------------------------------
    // Kanji results (chỉ khi mode == KANJI)
    // -------------------------------------------------------------------

    private List<KanjiResponse> findKanjiResults(String normalizedQuery, DictionaryUtil.SearchMode mode) {
        if (mode != DictionaryUtil.SearchMode.KANJI) return List.of();

        LinkedHashSet<String> distinctKanjiChars = new LinkedHashSet<>();
        normalizedQuery.codePoints().forEach(cp -> {
            if (distinctKanjiChars.size() < KANJI_RESULT_LIMIT && DictionaryUtil.isKanjiCodepoint(cp)) {
                distinctKanjiChars.add(new String(Character.toChars(cp)));
            }
        });
        if (distinctKanjiChars.isEmpty()) return List.of();

        List<Kanji> kanjis = kanjiRepository.findByCharacterIn(new ArrayList<>(distinctKanjiChars));
        Map<String, Kanji> byCharacter = new HashMap<>();
        for (Kanji kanji : kanjis) byCharacter.put(kanji.getCharacter(), kanji);

        List<Long> kanjiIds = kanjis.stream().map(Kanji::getId).toList();
        Map<Long, List<KanjiReadingResponse>> readingsByKanjiId = groupReadings(kanjiIds);

        List<KanjiResponse> results = new ArrayList<>();
        for (String character : distinctKanjiChars) {
            Kanji kanji = byCharacter.get(character);
            if (kanji != null) {
                results.add(mapper.toKanjiResponse(kanji, readingsByKanjiId.getOrDefault(kanji.getId(), List.of())));
            }
        }
        return results;
    }

    private Map<Long, List<KanjiReadingResponse>> groupReadings(List<Long> kanjiIds) {
        if (kanjiIds.isEmpty()) return Map.of();
        List<KanjiReading> readings = kanjiReadingRepository
                .findByKanji_IdInOrderByKanji_IdAscReadingTypeAscReadingTextAsc(kanjiIds);
        Map<Long, List<KanjiReadingResponse>> grouped = new LinkedHashMap<>();
        for (KanjiReading reading : readings) {
            grouped.computeIfAbsent(reading.getKanji().getId(), id -> new ArrayList<>())
                    .add(mapper.toKanjiReadingResponse(reading));
        }
        return grouped;
    }

    // -------------------------------------------------------------------
    // Vocabulary -> DTO theo lô (senses/glosses/kanji composition), tránh N+1
    // -------------------------------------------------------------------

    private List<VocabularyResponse> toVocabularyResponses(List<VocabularyEntry> entries) {
        if (entries.isEmpty()) return List.of();
        List<Long> ids = entries.stream().map(VocabularyEntry::getId).toList();

        List<VocabularySense> senses = vocabularySenseRepository
                .findByVocabularyEntry_IdInOrderByVocabularyEntry_IdAscSenseOrderAsc(ids);
        List<Long> senseIds = senses.stream().map(VocabularySense::getId).toList();
        List<VocabularyGloss> glosses = senseIds.isEmpty()
                ? List.of() : vocabularyGlossRepository.findBySense_IdIn(senseIds);
        List<SensePartOfSpeech> partsOfSpeech = senseIds.isEmpty()
                ? List.of() : sensePartOfSpeechRepository.findBySenseIdIn(senseIds);
        List<SenseDomain> domains = senseIds.isEmpty()
                ? List.of() : senseDomainRepository.findBySenseIdIn(senseIds);

        Map<Long, List<GlossResponse>> glossesBySense = new LinkedHashMap<>();
        for (VocabularyGloss gloss : glosses) {
            glossesBySense.computeIfAbsent(gloss.getSense().getId(), id -> new ArrayList<>())
                    .add(mapper.toGlossResponse(gloss));
        }

        Map<Long, List<String>> partsOfSpeechBySense = new LinkedHashMap<>();
        for (SensePartOfSpeech sensePos : partsOfSpeech) {
            String label = sensePos.getPartOfSpeech().getNameVi() != null
                    ? sensePos.getPartOfSpeech().getNameVi() : sensePos.getPartOfSpeech().getCode();
            partsOfSpeechBySense.computeIfAbsent(sensePos.getId().getSenseId(), id -> new ArrayList<>()).add(label);
        }

        Map<Long, List<String>> domainsBySense = new LinkedHashMap<>();
        for (SenseDomain senseDomain : domains) {
            String label = senseDomain.getDomain().getNameVi() != null
                    ? senseDomain.getDomain().getNameVi() : senseDomain.getDomain().getCode();
            domainsBySense.computeIfAbsent(senseDomain.getId().getSenseId(), id -> new ArrayList<>()).add(label);
        }

        Map<Long, List<VocabularySense>> sensesByVocabularyId = new LinkedHashMap<>();
        for (VocabularySense sense : senses) {
            sensesByVocabularyId.computeIfAbsent(sense.getVocabularyEntry().getId(), id -> new ArrayList<>()).add(sense);
        }

        Map<Long, List<SenseResponse>> sensesByVocabulary = new LinkedHashMap<>();
        for (Map.Entry<Long, List<VocabularySense>> vocabSenses : sensesByVocabularyId.entrySet()) {
            String preferredLanguage = preferredGlossLanguage(vocabSenses.getValue(), glossesBySense);
            List<SenseResponse> responses = new ArrayList<>();
            for (VocabularySense sense : vocabSenses.getValue()) {
                List<GlossResponse> allGlosses = glossesBySense.getOrDefault(sense.getId(), List.of());
                List<GlossResponse> glosses2 = preferredLanguage == null ? allGlosses
                        : allGlosses.stream().filter(g -> preferredLanguage.equals(g.getLanguageCode())).toList();
                // JMdict multilingual: mỗi sense có thể chỉ tồn tại trong 1 ngôn ngữ khác (ví dụ
                // sense riêng cho bản dịch tiếng Hà Lan). Bỏ qua sense không có gloss ở ngôn ngữ đã
                // chọn thay vì hiện một sense trống hoặc lẫn lộn nhiều thứ tiếng cùng lúc.
                if (glosses2.isEmpty()) continue;
                responses.add(mapper.toSenseResponse(sense,
                        partsOfSpeechBySense.getOrDefault(sense.getId(), List.of()),
                        domainsBySense.getOrDefault(sense.getId(), List.of()),
                        glosses2));
            }
            sensesByVocabulary.put(vocabSenses.getKey(), responses);
        }

        List<VocabularyKanji> composition = vocabularyKanjiRepository.findCompositionByVocabularyIds(ids);
        Map<Long, List<KanjiCompositionResponse>> compositionByVocabulary = new LinkedHashMap<>();
        for (VocabularyKanji vocabularyKanji : composition) {
            compositionByVocabulary.computeIfAbsent(vocabularyKanji.getVocabularyEntry().getId(), id -> new ArrayList<>())
                    .add(mapper.toKanjiCompositionResponse(vocabularyKanji));
        }

        return entries.stream()
                .map(entry -> mapper.toVocabularyResponse(entry,
                        sensesByVocabulary.getOrDefault(entry.getId(), List.of()),
                        compositionByVocabulary.getOrDefault(entry.getId(), List.of())))
                .toList();
    }

    /**
     * Ngôn ngữ gloss nên hiện cho một vocabulary: tiếng Việt nếu tồn tại ở bất kỳ sense nào,
     * ngược lại tiếng Anh, ngược lại {@code null} (giữ nguyên mọi ngôn ngữ — trường hợp hiếm khi
     * JMdict entry hoàn toàn không có eng/vie).
     */
    private static String preferredGlossLanguage(List<VocabularySense> vocabSenses,
                                                   Map<Long, List<GlossResponse>> glossesBySense) {
        for (String language : PREFERRED_GLOSS_LANGUAGES) {
            boolean present = vocabSenses.stream().anyMatch(sense ->
                    glossesBySense.getOrDefault(sense.getId(), List.of()).stream()
                            .anyMatch(gloss -> language.equals(gloss.getLanguageCode())));
            if (present) return language;
        }
        return null;
    }

    // -------------------------------------------------------------------
    // Lookup history (mục 13: dùng chung một history type, không tách riêng handwriting)
    // -------------------------------------------------------------------

    private void logHistory(String rawQuery, String normalizedQuery, DictionaryUtil.SearchMode mode,
                             int resultCount, Long userId) {
        DictionarySearchHistory history = DictionarySearchHistory.builder()
                .user(userId == null ? null : userRepository.getReferenceById(userId))
                .queryText(rawQuery)
                .normalizedQuery(normalizedQuery)
                .searchMode(mode.name())
                .resultCount(resultCount)
                .build();
        searchHistoryRepository.save(history);
    }

    private SearchResultResponse emptyResult(String rawQuery) {
        return SearchResultResponse.builder()
                .query(rawQuery)
                .searchMode(null)
                .kanjiResults(List.of())
                .vocabularyResults(PageResponse.<VocabularyResponse>builder()
                        .content(List.of())
                        .page(0)
                        .size(0)
                        .totalElements(0)
                        .last(true)
                        .build())
                .total(0)
                .build();
    }
}
