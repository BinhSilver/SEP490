package com.jlas.dictionary.service.impl;

import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.handwriting.KanjiStrokeIndex;
import com.jlas.dictionary.handwriting.StrokeMatcher;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.*;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cài đặt {@link DictionaryService}: tra cứu từ điển và duyệt giáo trình đã số hoá.
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

    // ---- Tra cứu từ điển ----
    private static final String PUBLISHED = "PUBLISHED";
    /** JMdict là multilingual; ưu tiên hiện tiếng Việt nếu có, sau đó tiếng Anh. */
    private static final List<String> PREFERRED_GLOSS_LANGUAGES = List.of("vie", "eng");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** Chặn trên tổng số candidate gộp từ mọi tier trong một lần search, tránh query quá nặng. */
    private static final int MAX_TOTAL_CANDIDATES = 200;
    private static final int KANJI_RESULT_LIMIT = 8;
    private static final int RELATED_VOCABULARY_LIMIT = 50;
    private static final int HANDWRITING_CANDIDATE_LIMIT = 8;

    // ---- Duyệt giáo trình đã số hoá ----
    /** owner_type của content_audio trỏ vocabulary_entry.id. */
    private static final String AUDIO_OWNER_VOCABULARY = "VOCABULARY_WORD";
    /** owner_type của content_audio trỏ content_item.id. */
    private static final String AUDIO_OWNER_CONTENT_ITEM = "CONTENT_ITEM";
    /** content_type của content_item là câu ví dụ. */
    private static final String CONTENT_TYPE_EXAMPLE = "GRAMMAR";
    /** content_type của content_item là mục đọc: chữ kana (giáo trình bảng chữ cái) hoặc đoạn chú thích. */
    private static final String CONTENT_TYPE_READING = "READING";
    /** content_type của content_item là mục nghe: lời hội thoại (có dialogue_line_detail) hoặc bài nghe. */
    private static final String CONTENT_TYPE_LISTENING = "LISTENING";
    /** Trạng thái duyệt công khai của textbook / lesson. */
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    /**
     * Xoá mềm. Không thêm cột mới: dùng luôn giá trị ARCHIVED mà chk_textbook_status / chk_lesson_status
     * đã cho phép. Bản ghi ARCHIVED biến mất khỏi MỌI danh sách, với learner lẫn CONTENT_CREATOR —
     * người biên soạn cần thấy đúng thứ học viên thấy khi kiểm nội dung. Chỉ màn hình quản lý
     * (ContentAdminService) mới liệt kê chúng, và quyền ở đó do @PreAuthorize quyết, không do tham số.
     */
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    /** Authority đầy đủ (CustomUserDetailsService nối tiền tố ROLE_ vào roles.name). */
    private static final String ROLE_CONTENT_CREATOR = "ROLE_CONTENT_CREATOR";
    /** tag.tag_type của chủ đề học. */
    private static final String TAG_TYPE_TOPIC = "SITUATION";

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
    private final KanjiStrokeIndex kanjiStrokeIndex;
    private final DictionaryMapper mapper;
    private final TextbookRepository textbookRepository;
    private final TagRepository tagRepository;
    private final UnitRepository unitRepository;
    private final LessonRepository lessonRepository;
    private final ContentVocabularyRepository contentVocabularyRepository;
    private final ContentItemRepository contentItemRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final DialogueRoleRepository dialogueRoleRepository;
    private final DialogueLineDetailRepository dialogueLineDetailRepository;
    private final DialogueLineChunkRepository dialogueLineChunkRepository;
    private final ContentAudioRepository contentAudioRepository;
    private final LessonKanjiRepository lessonKanjiRepository;
    private final ObjectMapper objectMapper;

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
    public List<KanjiResponse> recognizeHandwriting(HandwritingRequest request) {
        double scaleX = StrokeMatcher.FRAME / request.getWidth();
        double scaleY = StrokeMatcher.FRAME / request.getHeight();
        List<List<double[]>> strokes = new ArrayList<>();
        for (List<double[]> stroke : request.getStrokes()) {
            List<double[]> scaled = new ArrayList<>(stroke.size());
            for (double[] point : stroke) {
                if (!(point[0] >= 0 && point[0] <= request.getWidth() && point[1] >= 0 && point[1] <= request.getHeight())) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Toạ độ nét vẽ nằm ngoài canvas");
                }
                scaled.add(new double[]{point[0] * scaleX, point[1] * scaleY});
            }
            strokes.add(scaled);
        }

        List<String> ranked = StrokeMatcher.rank(kanjiStrokeIndex.refs(), strokes, HANDWRITING_CANDIDATE_LIMIT);
        if (ranked.isEmpty()) return List.of();

        Map<String, Kanji> byCharacter = new HashMap<>();
        for (Kanji kanji : kanjiRepository.findByCharacterIn(ranked)) byCharacter.put(kanji.getCharacter(), kanji);
        Map<Long, List<KanjiReadingResponse>> readings = groupReadings(
                byCharacter.values().stream().map(Kanji::getId).toList());

        List<KanjiResponse> candidates = new ArrayList<>();
        for (String character : ranked) {
            Kanji kanji = byCharacter.get(character);
            if (kanji != null) {
                candidates.add(mapper.toKanjiResponse(kanji, readings.getOrDefault(kanji.getId(), List.of())));
            }
        }
        return candidates;
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

    // ===================================================================
    // Duyệt giáo trình đã số hoá
    // ===================================================================

    @Override
    @Transactional(readOnly = true)
    public List<TextbookResponse> listTextbooks() {
        List<Textbook> textbooks = textbookRepository.findLibraryTextbooks(canViewDraft(), STATUS_PUBLISHED);
        if (textbooks.isEmpty()) return Collections.emptyList();

        List<Long> textbookIds = textbooks.stream().map(Textbook::getId).toList();
        Map<Long, Long> unitCountByTextbook = new HashMap<>();
        for (Object[] row : textbookRepository.countUnitsByTextbookIds(textbookIds)) {
            unitCountByTextbook.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, Long> situationLessonsByTextbook = new HashMap<>();
        for (Object[] row : textbookRepository.countSituationTaggedLessonsByTextbookIds(textbookIds, TAG_TYPE_TOPIC)) {
            situationLessonsByTextbook.put((Long) row[0], (Long) row[1]);
        }
        return textbooks.stream()
                .map(t -> mapper.toTextbookResponse(t,
                        unitCountByTextbook.getOrDefault(t.getId(), 0L),
                        isTopicBased(t, situationLessonsByTextbook.getOrDefault(t.getId(), 0L))))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TextbookUnitsResponse getTextbookUnits(Long textbookId) {
        boolean viewDraft = canViewDraft();
        // Giáo trình đã lưu trữ coi như không tồn tại ở màn hình duyệt, kể cả với CONTENT_CREATOR.
        Textbook textbook = (viewDraft
                ? textbookRepository.findByIdAndStatusNot(textbookId, STATUS_ARCHIVED)
                : textbookRepository.findByIdAndStatus(textbookId, STATUS_PUBLISHED))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Textbook not found: " + textbookId));
        if (textbookRepository.countOnyomiLessonsByTextbookId(textbookId) > 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Textbook not found: " + textbookId);
        }

        List<Unit> units = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId);
        List<Lesson> lessons = viewDraft
                ? lessonRepository.findByTextbookIdAndStatusNot(textbookId, STATUS_ARCHIVED)
                : lessonRepository.findByTextbookIdAndStatus(textbookId, STATUS_PUBLISHED);

        Map<Long, List<LessonSummaryResponse>> lessonsByUnit = new LinkedHashMap<>();
        for (Lesson lesson : lessons) {
            lessonsByUnit.computeIfAbsent(lesson.getUnit().getId(), k -> new ArrayList<>())
                    .add(mapper.toLessonSummaryResponse(lesson));
        }

        return TextbookUnitsResponse.builder()
                .textbook(mapper.toTextbookResponse(textbook, (long) units.size()))
                .units(units.stream()
                        .map(u -> mapper.toUnitResponse(
                                u, lessonsByUnit.getOrDefault(u.getId(), Collections.emptyList())))
                        .toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopicResponse> listTopics() {
        List<Tag> topics = tagRepository.findByTagTypeOrderByCodeAsc(TAG_TYPE_TOPIC);
        Map<Long, Long> countByTag = new HashMap<>();
        List<Object[]> rows = canViewDraft()
                ? tagRepository.countLessonsByTagTypeAndStatusNot(TAG_TYPE_TOPIC, STATUS_ARCHIVED)
                : tagRepository.countLessonsByTagTypeAndStatus(TAG_TYPE_TOPIC, STATUS_PUBLISHED);
        for (Object[] row : rows) countByTag.put((Long) row[0], (Long) row[1]);
        // Chủ đề 0 bài vẫn trả về với lessonCount = 0 (chủ ý, không ẩn).
        return topics.stream()
                .map(t -> mapper.toTopicResponse(t, countByTag.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TopicLessonsResponse getTopicLessons(String code) {
        // NOT_FOUND chỉ khi tag không tồn tại; tag tồn tại mà không có bài được xem → 200 với lessons rỗng.
        Tag tag = tagRepository.findByCodeAndTagType(code, TAG_TYPE_TOPIC)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Topic not found: " + code));
        List<Lesson> lessons = canViewDraft()
                ? lessonRepository.findByTagCodeAndStatusNot(code, STATUS_ARCHIVED)
                : lessonRepository.findByTagCodeAndStatus(code, STATUS_PUBLISHED);
        return TopicLessonsResponse.builder()
                .topic(mapper.toTopicResponse(tag, (long) lessons.size()))
                .lessons(lessons.stream().map(mapper::toTopicLessonResponse).toList())
                .build();
    }

    /** Ghi: quyền CONTENT_CREATOR đã chặn ở controller (@PreAuthorize); class-level @Transactional (read-write) áp dụng. */
    @Override
    public TextbookResponse updateTextbookStatus(Long textbookId, String status) {
        Textbook textbook = textbookRepository.findById(textbookId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Textbook not found: " + textbookId));
        // Về DRAFT/ARCHIVED không cascade xuống lesson: bật lại giáo trình là các bài về như cũ.
        textbook.setStatus(status);
        long unitCount = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId).size();
        return mapper.toTextbookResponse(textbookRepository.save(textbook), unitCount);
    }

    @Override
    public LessonSummaryResponse updateLessonStatus(Long lessonId, String status) {
        Lesson lesson = lessonRepository.findWithUnitById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));
        Textbook textbook = lesson.getUnit().getTextbook();
        if (STATUS_PUBLISHED.equals(status) && !STATUS_PUBLISHED.equals(textbook.getStatus())) {
            // Bài PUBLISHED trong giáo trình chưa công bố = bài mồ côi, learner không vào được → chặn.
            throw new ApiException(ErrorCode.CONFLICT,
                    "Giáo trình \"" + textbook.getTitle() + "\" đang ở trạng thái " + textbook.getStatus()
                            + ". Hãy công bố giáo trình trước rồi mới công bố bài học.");
        }
        lesson.setStatus(status);
        return mapper.toLessonSummaryResponse(lessonRepository.save(lesson));
    }

    /**
     * Số query cố định cho một bài (không N+1), 12 câu: lesson, từ vựng, audio từ vựng,
     * liên kết từ↔Kanji (kèm Kanji + bộ thủ), cách đọc Kanji, ngữ pháp, vai, lời thoại,
     * cụm tách nghĩa, câu ví dụ, mục đọc (READING), audio content_item
     * (câu ví dụ + lời thoại gộp trong MỘT lời gọi vì cùng owner_type).
     * Learner bị chặn ở cả hai tầng: lesson DRAFT trong textbook PUBLISHED, và lesson thuộc textbook DRAFT.
     * <p>
     * Khác các danh sách ở trên, chỗ này CỐ Ý vẫn cho CONTENT_CREATOR mở bài ARCHIVED khi biết URL:
     * bài đã lưu trữ biến mất khỏi mọi danh sách, nhưng từ màn hình quản lý phải xem lại được nội dung
     * trước khi quyết định khôi phục. Learner vẫn nhận 404 như bài không tồn tại.
     */
    @Override
    @Transactional(readOnly = true)
    public LessonContentResponse getLessonContent(Long lessonId) {
        Lesson lesson = lessonRepository.findWithUnitById(lessonId)
                .filter(l -> !("AMON".equals(l.getLessonType()) && "ONYOMI".equals(l.getSkill())))
                .filter(l -> canViewDraft() || isPublished(l))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));

        LessonContentResponse response = mapper.toLessonContentResponse(lesson);
        List<VocabularyEntry> vocabularies = contentVocabularyRepository.findVocabularyByLessonId(lessonId);
        response.setKanjis(loadKanjis(lessonId, vocabularies));
        response.setVocabularies(toVocabularies(vocabularies));
        response.setGrammarPoints(loadGrammarPoints(lessonId));
        response.setDialogueRoles(loadDialogueRoles(lessonId));

        // Câu ví dụ, mục đọc (bảng chữ kana / chú thích), mục nghe và lời thoại đều là content_item
        // → audio lấy chung MỘT lần theo CONTENT_ITEM cho cả bốn.
        List<ContentItem> examples = contentItemRepository.findByLessonIdAndContentType(lessonId, CONTENT_TYPE_EXAMPLE);
        List<ContentItem> readings = contentItemRepository.findByLessonIdAndContentType(lessonId, CONTENT_TYPE_READING);
        List<DialogueLineDetail> dialogueLines = dialogueLineDetailRepository.findByLessonId(lessonId);
        // Mục LISTENING không có dialogue_line_detail (transcript / file audio bài nghe) — trước đây không
        // được trả về nên các bài nghe chỉ có loại mục này hiện trống.
        Set<Long> dialogueItemIds = dialogueLines.stream()
                .map(DialogueLineDetail::getContentItemId)
                .collect(Collectors.toSet());
        List<ContentItem> listenings = contentItemRepository.findByLessonIdAndContentType(lessonId, CONTENT_TYPE_LISTENING)
                .stream().filter(ci -> !dialogueItemIds.contains(ci.getId())).toList();

        List<Long> contentItemIds = new ArrayList<>(
                examples.size() + readings.size() + listenings.size() + dialogueLines.size());
        examples.forEach(ci -> contentItemIds.add(ci.getId()));
        readings.forEach(ci -> contentItemIds.add(ci.getId()));
        listenings.forEach(ci -> contentItemIds.add(ci.getId()));
        dialogueLines.forEach(d -> contentItemIds.add(d.getContentItemId()));
        Map<Long, List<ContentAudioResponse>> audioByItem = loadAudioByOwner(AUDIO_OWNER_CONTENT_ITEM, contentItemIds);

        // Mã gốc + URL nguồn của mục đọc/nghe (chỉ có ở dữ liệu nhập từ nguồn ngoài): để gắn câu hỏi luyện tập
        // vào đúng bài đọc/bài nghe và đúng file audio của từng câu.
        List<Long> sourcedIds = new ArrayList<>(readings.size() + listenings.size());
        readings.forEach(ci -> sourcedIds.add(ci.getId()));
        listenings.forEach(ci -> sourcedIds.add(ci.getId()));
        Map<Long, String[]> sourceByItem = loadContentSources(sourcedIds);

        List<ExampleSentenceResponse> readingResponses = toExampleSentences(readings, audioByItem);
        List<ExampleSentenceResponse> listeningResponses = toExampleSentences(listenings, audioByItem);
        readingResponses.forEach(r -> r.setStableCode(sourceStableCode(sourceByItem, r.getContentItemId())));
        listeningResponses.forEach(r -> r.setStableCode(sourceStableCode(sourceByItem, r.getContentItemId())));

        response.setExampleSentences(toExampleSentences(examples, audioByItem));
        response.setReadingItems(readingResponses);
        response.setListeningItems(listeningResponses);
        response.setDialogueLines(toDialogueLines(dialogueLines, audioByItem));
        response.setPracticeQuestions(loadPracticeQuestions(lessonId, listenings, sourceByItem, audioByItem));
        response.setQuizAvailable(lessonRepository.countPublishedLessonQuizzes(lessonId) > 0);
        return response;
    }

    /** content_item id → {stable_code, source_url}. */
    private Map<Long, String[]> loadContentSources(List<Long> contentItemIds) {
        if (contentItemIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String[]> result = new HashMap<>();
        for (Object[] row : contentItemRepository.findSourcesByContentItemIds(contentItemIds)) {
            result.put(((Number) row[0]).longValue(), new String[]{(String) row[1], (String) row[2]});
        }
        return result;
    }

    private static String sourceStableCode(Map<Long, String[]> sourceByItem, Long contentItemId) {
        String[] source = sourceByItem.get(contentItemId);
        return source == null ? null : source[0];
    }

    /**
     * Câu hỏi luyện tập từ quiz của bài. Phương án lấy ở context_json.options (đủ cả câu 5–9 phương án), thiếu
     * thì dùng option_1..4. Audio của câu: context_json.audio_hint[].url là URL gốc của file, khớp source_url của
     * mục nghe "Audio N" mang file đó → lấy đường dẫn local trong content_audio của mục ấy.
     */
    private List<LessonContentResponse.PracticeQuestion> loadPracticeQuestions(
            Long lessonId, List<ContentItem> listenings, Map<Long, String[]> sourceByItem,
            Map<Long, List<ContentAudioResponse>> audioByItem) {
        List<Object[]> rows = lessonRepository.findPracticeQuestionRows(lessonId, canViewDraft());
        if (rows.isEmpty()) return Collections.emptyList();

        Map<String, Long> audioItemBySourceUrl = new HashMap<>();
        for (ContentItem item : listenings) {
            String[] source = sourceByItem.get(item.getId());
            if (source != null && source[1] != null && !audioByItem.getOrDefault(item.getId(), List.of()).isEmpty()) {
                audioItemBySourceUrl.putIfAbsent(source[1], item.getId());
            }
        }

        List<LessonContentResponse.PracticeQuestion> questions = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            JsonNode context = readJson((String) row[9]);
            List<String> options = new ArrayList<>();
            List<Integer> correctOptions = new ArrayList<>();
            JsonNode contextOptions = context == null ? null : context.path("options");
            if (contextOptions != null && contextOptions.isArray() && !contextOptions.isEmpty()) {
                for (JsonNode option : contextOptions) {
                    options.add(option.path("text").isTextual() ? option.path("text").asText() : "");
                    if (option.path("is_correct").asBoolean(false)) correctOptions.add(options.size());
                }
            } else {
                for (int i = 3; i <= 6; i++) {
                    if (row[i] != null && !row[i].toString().isBlank()) options.add(row[i].toString());
                }
            }
            if (correctOptions.isEmpty()) {
                Integer single = row[7] != null ? Integer.valueOf(((Number) row[7]).intValue())
                        : (context != null && context.path("answer").isInt() ? context.path("answer").asInt() : null);
                if (single != null) correctOptions.add(single);
            }
            // Câu chọn hình: nguồn không có ảnh nên không có phương án để bấm — giữ đáp án dạng chữ "Hình số N"
            // (hiện sau nút "Xem đáp án") thay vì dựng nút giả.
            String answerText = row[10] == null || row[10].toString().isBlank() ? null : row[10].toString();
            if (options.isEmpty() && answerText == null && correctOptions.size() == 1) {
                answerText = ("chon_hinh".equals(textOrNull(context, "loai_cau")) ? "Hình số " : "Phương án ")
                        + correctOptions.get(0);
            }
            // Đáp án trỏ ra ngoài danh sách phương án (nguồn mất phương án) → coi như chưa có đáp án chắc chắn,
            // để thẻ không báo "sai" cho một lựa chọn đúng.
            int optionCount = options.size();
            correctOptions.removeIf(n -> n < 1 || n > optionCount);
            Integer correct = correctOptions.size() == 1 ? correctOptions.get(0) : null;

            Long audioItemId = null;
            if (context != null) {
                for (JsonNode hint : context.path("audio_hint")) {
                    audioItemId = audioItemBySourceUrl.get(hint.path("url").asText(""));
                    if (audioItemId != null) break;
                }
            }
            List<ContentAudioResponse> audios = audioItemId == null ? List.of() : audioByItem.getOrDefault(audioItemId, List.of());

            questions.add(LessonContentResponse.PracticeQuestion.builder()
                    .id(((Number) row[0]).longValue())
                    .orderIndex(row[1] == null ? null : ((Number) row[1]).intValue())
                    .questionText((String) row[2])
                    .translation(textOrNull(context, "question_translation"))
                    .options(options)
                    .correctOption(correct)
                    .correctOptions(correctOptions)
                    .answerText(answerText)
                    .explanation(row[8] == null || row[8].toString().isBlank() ? null : row[8].toString())
                    .parentCode(textOrNull(context, "parent_code"))
                    .audioUrl(audios.isEmpty() ? null : audios.get(0).getAudioUrl())
                    .audioContentItemId(audios.isEmpty() ? null : audioItemId)
                    .build());
        }
        assignReadingTargets(questions);
        return questions;
    }

    private static final java.util.regex.Pattern KANJI_RUN = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff\\u3005]+");

    /**
     * Gạch chân đúng chữ ở dạng "cách đọc chữ Hán" (phương án toàn kana, có một đáp án): với mỗi cụm chữ Hán trong đề,
     * thử cụm + phần okurigana khớp đuôi đáp án (赤い ↔ あかい), và chọn cách viết mà từ điển có cách đọc đúng bằng
     * đáp án (空 ↔ そら, không phải 見). Một query cho cả bài; không có cụm nào khớp thì để null.
     */
    private void assignReadingTargets(List<LessonContentResponse.PracticeQuestion> questions) {
        // Danh sách cặp, không dùng Map: PracticeQuestion là @Data (equals theo nội dung) nên hai câu giống hệt nhau
        // sẽ đè nhau nếu làm khoá.
        List<Map.Entry<LessonContentResponse.PracticeQuestion, List<String>>> candidates = new ArrayList<>();
        Set<String> expressions = new HashSet<>();
        for (LessonContentResponse.PracticeQuestion q : questions) {
            String text = q.getQuestionText();
            List<String> options = q.getOptions();
            if (q.getCorrectOption() == null || text == null || options.isEmpty()
                    || !options.stream().allMatch(DictionaryServiceImpl::isKanaOnly)) continue;
            String answer = options.get(q.getCorrectOption() - 1).strip();
            List<String> exprs = new ArrayList<>();
            java.util.regex.Matcher run = KANJI_RUN.matcher(text);
            while (run.find()) {
                String following = text.substring(run.end());
                for (int k = Math.min(answer.length() - 1, following.length()); k >= 0; k--) {
                    if (k == 0 || answer.endsWith(following.substring(0, k))) {
                        String expr = run.group() + following.substring(0, k);
                        exprs.add(expr);
                        expressions.add(DictionaryUtil.normalizeText(expr));
                    }
                }
            }
            if (!exprs.isEmpty()) candidates.add(Map.entry(q, exprs));
        }
        if (expressions.isEmpty()) return;

        Map<String, Set<String>> readingsByExpression = new HashMap<>();
        for (Object[] row : vocabularyEntryRepository.findReadingsByNormalizedExpressions(expressions)) {
            readingsByExpression.computeIfAbsent((String) row[0], k -> new HashSet<>()).add((String) row[1]);
        }
        for (Map.Entry<LessonContentResponse.PracticeQuestion, List<String>> candidate : candidates) {
            LessonContentResponse.PracticeQuestion q = candidate.getKey();
            String answer = DictionaryUtil.normalizeReading(q.getOptions().get(q.getCorrectOption() - 1));
            candidate.getValue().stream()
                    .filter(e -> readingsByExpression.getOrDefault(DictionaryUtil.normalizeText(e), Set.of()).contains(answer))
                    .max(java.util.Comparator.comparingInt(String::length))
                    .ifPresent(q::setTargetText);
        }
    }

    private static boolean isKanaOnly(String value) {
        if (value == null || value.isBlank()) return false;
        return value.strip().codePoints().allMatch(cp ->
                (cp >= 0x3040 && cp <= 0x30ff) || cp == 0x30fc || cp == 0x30fb || Character.isWhitespace(cp));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;   // context_json hỏng chỉ làm mất bản dịch/audio của câu, không làm hỏng cả trang
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /**
     * Kanji của bài: kanji gắn tay (lesson_kanji, theo order_index người biên soạn đặt) đứng trước, rồi
     * tới kanji xuất hiện trong từ vựng của bài (content_vocabulary → vocabulary_kanji → kanji); chữ có ở
     * cả hai nguồn chỉ hiện một lần. Cách đọc On/Kun lấy một lần theo tập kanji_id.
     * Dữ liệu Kanji từ KANJIDIC2 (EDRDG, CC BY-SA 4.0).
     */
    private List<KanjiResponse> loadKanjis(Long lessonId, List<VocabularyEntry> entries) {
        Map<Long, VocabularyEntry> entryById = new LinkedHashMap<>();
        entries.forEach(v -> entryById.put(v.getId(), v));

        // Kanji theo id (giữ thứ tự xuất hiện) + các từ của bài chứa từng chữ.
        Map<Long, Kanji> kanjiById = new LinkedHashMap<>();
        Map<Long, List<KanjiWordResponse>> wordsByKanji = new LinkedHashMap<>();
        for (LessonKanji link : lessonKanjiRepository.findByLessonIdWithKanji(lessonId)) {
            kanjiById.putIfAbsent(link.getKanji().getId(), link.getKanji());
        }
        for (VocabularyKanji link : entryById.isEmpty() ? List.<VocabularyKanji>of()
                : vocabularyKanjiRepository.findByVocabularyIdIn(entryById.keySet())) {
            Kanji kanji = link.getKanji();
            kanjiById.putIfAbsent(kanji.getId(), kanji);
            wordsByKanji.computeIfAbsent(kanji.getId(), k -> new ArrayList<>())
                    .add(mapper.toKanjiWordResponse(
                            entryById.get(link.getId().getVocabularyId()), link.getId().getCharacterPosition()));
        }
        if (kanjiById.isEmpty()) return Collections.emptyList();

        Map<Long, List<KanjiReadingResponse>> readingsByKanji = new LinkedHashMap<>();
        for (KanjiReading reading : kanjiReadingRepository
                .findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(kanjiById.keySet())) {
            readingsByKanji.computeIfAbsent(reading.getKanji().getId(), k -> new ArrayList<>())
                    .add(mapper.toKanjiReadingResponse(reading));
        }

        return kanjiById.values().stream()
                .map(k -> mapper.toKanjiResponse(
                        k,
                        readingsByKanji.getOrDefault(k.getId(), Collections.emptyList()),
                        wordsByKanji.getOrDefault(k.getId(), Collections.emptyList())))
                .toList();
    }

    private List<LessonVocabularyResponse> toVocabularies(List<VocabularyEntry> entries) {
        Map<Long, List<ContentAudioResponse>> audioByOwner = loadAudioByOwner(
                AUDIO_OWNER_VOCABULARY, entries.stream().map(VocabularyEntry::getId).toList());
        return entries.stream()
                .map(v -> mapper.toLessonVocabularyResponse(
                        v, audioByOwner.getOrDefault(v.getId(), Collections.emptyList())))
                .toList();
    }

    private List<GrammarPointResponse> loadGrammarPoints(Long lessonId) {
        return grammarPointRepository.findByLessonIdOrderByOrderIndexAsc(lessonId).stream()
                .map(mapper::toGrammarPointResponse)
                .toList();
    }

    private List<DialogueRoleResponse> loadDialogueRoles(Long lessonId) {
        return dialogueRoleRepository.findByLessonId(lessonId).stream()
                .map(mapper::toDialogueRoleResponse)
                .toList();
    }

    private List<ExampleSentenceResponse> toExampleSentences(List<ContentItem> examples,
                                                             Map<Long, List<ContentAudioResponse>> audioByItem) {
        return examples.stream()
                .map(ci -> mapper.toExampleSentenceResponse(
                        ci, audioByItem.getOrDefault(ci.getId(), Collections.emptyList())))
                .toList();
    }

    private List<DialogueLineResponse> toDialogueLines(List<DialogueLineDetail> details,
                                                       Map<Long, List<ContentAudioResponse>> audioByItem) {
        if (details.isEmpty()) return Collections.emptyList();

        List<Long> itemIds = details.stream().map(DialogueLineDetail::getContentItemId).toList();
        Map<Long, List<DialogueChunkResponse>> chunksByItem = new LinkedHashMap<>();
        for (DialogueLineChunk chunk : dialogueLineChunkRepository
                .findByContentItemIdInOrderByContentItemIdAscOrderIndexAsc(itemIds)) {
            chunksByItem.computeIfAbsent(chunk.getContentItem().getId(), k -> new ArrayList<>())
                    .add(mapper.toDialogueChunkResponse(chunk));
        }

        return details.stream()
                .map(d -> mapper.toDialogueLineResponse(
                        d,
                        chunksByItem.getOrDefault(d.getContentItemId(), Collections.emptyList()),
                        audioByItem.getOrDefault(d.getContentItemId(), Collections.emptyList())))
                .toList();
    }

    /** CONTENT_CREATOR thấy mọi trạng thái; mọi vai khác (kể cả ADMIN, theo quyết định team) và người chưa đăng nhập chỉ thấy PUBLISHED. */
    private static boolean canViewDraft() {
        return CurrentUser.hasRole(ROLE_CONTENT_CREATOR);
    }

    /**
     * Giáo trình thuộc nhóm "Theo chủ đề" khi hội ĐỦ HAI điều kiện: không có cấp độ JLPT,
     * và có ít nhất một bài gắn tag SITUATION. Chỉ vế sau là chưa đủ — Sekai N5/N4 cũng có bài
     * gắn tag SITUATION nhưng có jlpt_level nên vẫn thuộc nhóm theo cấp độ.
     */
    private static boolean isTopicBased(Textbook textbook, long situationTaggedLessons) {
        String level = textbook.getJlptLevel();
        return (level == null || level.isBlank()) && situationTaggedLessons > 0;
    }

    /** Bài chỉ công khai khi chính nó lẫn giáo trình chứa nó đều PUBLISHED. Yêu cầu lesson đã fetch join unit.textbook. */
    private static boolean isPublished(Lesson lesson) {
        return STATUS_PUBLISHED.equals(lesson.getStatus())
                && lesson.getUnit() != null
                && lesson.getUnit().getTextbook() != null
                && STATUS_PUBLISHED.equals(lesson.getUnit().getTextbook().getStatus());
    }

    /**
     * Gộp audio theo owner_id cho MỘT loại owner_type. owner_id là khoá đa hình
     * (VOCABULARY_WORD → vocabulary_entry.id, CONTENT_ITEM → content_item.id) nên mỗi loại
     * phải query riêng; không bao giờ trộn hai tập id khác owner_type vào cùng một lời gọi.
     */
    private Map<Long, List<ContentAudioResponse>> loadAudioByOwner(String ownerType, Collection<Long> ownerIds) {
        if (ownerIds.isEmpty()) return Collections.emptyMap();
        Map<Long, List<ContentAudioResponse>> result = new LinkedHashMap<>();
        for (ContentAudio audio : contentAudioRepository.findByOwnerTypeAndOwnerIdIn(ownerType, ownerIds)) {
            result.computeIfAbsent(audio.getOwnerId(), k -> new ArrayList<>())
                    .add(mapper.toContentAudioResponse(audio));
        }
        return result;
    }
}
