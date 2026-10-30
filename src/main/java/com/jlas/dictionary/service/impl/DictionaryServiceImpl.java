package com.jlas.dictionary.service.impl;

import com.jlas.auth.repository.UserRepository;
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
import com.jlas.dictionary.util.VerbDeinflector;
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
    /**
     * Ưu tiên hiện tiếng Việt nếu có, sau đó tiếng Anh. Từ điển import dùng mã ISO 639-2
     * (vie/eng), dữ liệu giáo trình dùng mã 2 ký tự (vi/en) — coi là cùng một ngôn ngữ.
     */
    private static final List<Set<String>> PREFERRED_GLOSS_LANGUAGES =
            List.of(Set.of("vie", "vi"), Set.of("eng", "en"));
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** Chặn trên tổng số candidate gộp từ mọi tier trong một lần search, tránh query quá nặng. */
    private static final int MAX_TOTAL_CANDIDATES = 200;
    private static final int KANJI_RESULT_LIMIT = 8;
    private static final int RELATED_VOCABULARY_LIMIT = 50;
    private static final int HANDWRITING_CANDIDATE_LIMIT = 8;
    /** Số Kanji tối đa trả về cho một lần tra bộ thủ (bộ phận 口 có gần 3.000 Kanji). */
    private static final int COMPONENT_RESULT_LIMIT = 300;
    private static final int MAX_SELECTED_COMPONENTS = 10;
    /** Độ dài tối đa (code point) của query khi tách thành từ con: tối đa 78 chuỗi con trong IN. */
    private static final int SEGMENT_MAX_QUERY_LENGTH = 12;
    /** Số thể từ điển ứng viên tối đa đưa vào IN, và số từ tối đa thêm vào kết quả từ chúng. */
    private static final int INFLECTION_MAX_TERMS = 60;
    private static final int INFLECTION_RESULT_LIMIT = 10;

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
    private final KanjiComponentRepository kanjiComponentRepository;
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
    public List<KanjiComponentResponse> listKanjiComponents() {
        return kanjiComponentRepository.findAllForPicker().stream()
                .map(component -> KanjiComponentResponse.builder()
                        .id(component.getId().intValue())
                        .character(component.getCharacter())
                        .strokeCount(component.getStrokeCount().intValue())
                        .nameVi(component.getRadical() == null ? null : component.getRadical().getNameVi())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ComponentSearchResponse searchByComponents(List<Integer> componentIds) {
        List<Short> ids = componentIds == null ? List.of() : componentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(Integer::shortValue)
                .toList();
        if (ids.isEmpty()) {
            return ComponentSearchResponse.builder()
                    .total(0).kanji(List.of()).availableComponentIds(List.of()).build();
        }
        if (ids.size() > MAX_SELECTED_COMPONENTS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Chỉ chọn tối đa " + MAX_SELECTED_COMPONENTS + " bộ thủ");
        }
        long total = kanjiComponentRepository.countKanjiWithAll(ids, ids.size());
        List<ComponentSearchResponse.Item> kanji = total == 0 ? List.of()
                : kanjiComponentRepository.findKanjiWithAll(ids, ids.size(), COMPONENT_RESULT_LIMIT).stream()
                        .map(row -> ComponentSearchResponse.Item.builder()
                                .character(row.getKanjiCharacter())
                                .strokeCount(row.getStrokeCount())
                                .hanViet(row.getHanViet())
                                .jlptLevel(row.getJlptLevel())
                                .build())
                        .toList();
        List<Integer> available = total == 0 ? List.of()
                : kanjiComponentRepository.findCooccurringComponentIds(ids, ids.size()).stream()
                        .map(Number::intValue)
                        .sorted()
                        .toList();
        return ComponentSearchResponse.builder()
                .total(total).kanji(kanji).availableComponentIds(available).build();
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
            case ROMAJI -> {
                collectRomajiModeCandidates(normalizedQuery, limit, collected);
                // Chữ Latin không dấu vừa có thể là romaji vừa là tiếng Việt/Anh ("con", "xe"). Chỉ
                // dùng các tier có index để "taberu" không phải quét toàn bảng gloss.
                if (collected.size() < limit) {
                    collectMeaningModeCandidates(normalizedQuery, limit, collected, false);
                }
            }
            case VIETNAMESE -> collectMeaningModeCandidates(normalizedQuery, limit, collected, true);
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
            collectDictionaryForms(query, false, limit, collected);
            if (collected.size() >= limit) return;
            addAll(collected, vocabularyEntryRepository
                    .findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
                            query, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
            if (collected.size() >= limit) return;
            String anchor = firstKanjiInTable(query);
            addAll(collected, anchor != null
                    ? vocabularyKanjiRepository.findVocabularyContainingViaKanji(
                            anchor, escapeLike(query), PageRequest.of(0, remaining(collected, limit)))
                    : vocabularyEntryRepository
                            .findByNormalizedExpressionContainingAndDictionaryStatusOrderByIdAsc(
                                    query, PUBLISHED, PageRequest.of(0, remaining(collected, limit))));
            if (collected.isEmpty()) collectSegmentedWords(query, limit, collected);
        }
    }

    /**
     * Cụm không có trong từ điển (防錆剤): tách thành các từ có trong từ điển theo kiểu khớp dài
     * nhất từ trái sang phải (防錆 + 剤), giống Mazii, thay vì chỉ còn các thẻ Kanji lẻ.
     * Một query IN cho mọi chuỗi con.
     */
    private void collectSegmentedWords(String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        int[] codePoints = query.codePoints().limit(SEGMENT_MAX_QUERY_LENGTH).toArray();
        Set<String> substrings = new HashSet<>();
        for (int start = 0; start < codePoints.length; start++) {
            for (int end = start + 1; end <= codePoints.length; end++) {
                substrings.add(new String(codePoints, start, end - start));
            }
        }
        Map<String, List<VocabularyEntry>> byExpression = new HashMap<>();
        for (VocabularyEntry entry : vocabularyEntryRepository
                .findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(substrings, PUBLISHED)) {
            byExpression.computeIfAbsent(entry.getNormalizedExpression(), key -> new ArrayList<>()).add(entry);
        }

        int start = 0;
        while (start < codePoints.length && collected.size() < limit) {
            int matchedEnd = -1;
            for (int end = codePoints.length; end > start && matchedEnd < 0; end--) {
                if (byExpression.containsKey(new String(codePoints, start, end - start))) matchedEnd = end;
            }
            if (matchedEnd < 0) {
                start++;
                continue;
            }
            List<VocabularyEntry> words = byExpression.get(new String(codePoints, start, matchedEnd - start));
            addAll(collected, words.subList(0, Math.min(words.size(), remaining(collected, limit))));
            start = matchedEnd;
        }
    }

    /**
     * Dạng đã chia (行きます, 行った, 行き, 食べませんでした) -&gt; thể từ điển (行く, 食べる). Ứng viên của
     * {@link VerbDeinflector} chỉ được nhận khi có trong từ điển với từ loại khớp; một query IN.
     */
    private void collectDictionaryForms(String query, boolean byReading, int limit,
                                        LinkedHashMap<Long, VocabularyEntry> collected) {
        List<VerbDeinflector.Candidate> candidates = VerbDeinflector.deinflect(query).stream()
                .limit(INFLECTION_MAX_TERMS).toList();
        if (candidates.isEmpty()) return;

        Map<String, Set<VerbDeinflector.Form>> formsByTerm = new HashMap<>();
        for (VerbDeinflector.Candidate candidate : candidates) formsByTerm.put(candidate.term(), candidate.forms());
        List<VocabularyEntryRepository.PartOfSpeechRow> rows = byReading
                ? vocabularyEntryRepository.findPartsOfSpeechByReadingIn(formsByTerm.keySet())
                : vocabularyEntryRepository.findPartsOfSpeechByExpressionIn(formsByTerm.keySet());

        Map<String, Set<Long>> idsByTerm = new HashMap<>();
        for (VocabularyEntryRepository.PartOfSpeechRow row : rows) {
            Set<VerbDeinflector.Form> forms = formsByTerm.get(row.getTerm());
            if (forms != null && forms.stream().anyMatch(form -> form.matchesPartOfSpeech(row.getPartOfSpeech()))) {
                idsByTerm.computeIfAbsent(row.getTerm(), key -> new LinkedHashSet<>()).add(row.getVocabularyId());
            }
        }
        List<Long> ids = candidates.stream()
                .flatMap(candidate -> idsByTerm.getOrDefault(candidate.term(), Set.of()).stream())
                .distinct()
                .limit(Math.min(INFLECTION_RESULT_LIMIT, remaining(collected, limit)))
                .toList();
        addAllById(collected, ids);
    }

    /** Kanji đầu tiên của query có trong bảng kanji (để neo tier "chứa"), hoặc null. */
    private String firstKanjiInTable(String query) {
        return query.codePoints()
                .filter(DictionaryUtil::isKanjiCodepoint)
                .mapToObj(cp -> new String(Character.toChars(cp)))
                .filter(character -> kanjiRepository.findByCharacter(character).isPresent())
                .findFirst()
                .orElse(null);
    }

    private void collectReadingModeCandidates(
            String query, int limit, LinkedHashMap<Long, VocabularyEntry> collected) {
        String readingKey = DictionaryUtil.normalizeReading(query);
        vocabularyEntryRepository.findFirstByNormalizedReadingAndDictionaryStatus(readingKey, PUBLISHED)
                .ifPresent(e -> collected.put(e.getId(), e));
        if (collected.size() >= limit) return;

        // Kana không có Kanji neo nên dễ nhận nhầm (まえ -> まう): chỉ chia ngược khi không có từ trùng.
        if (collected.isEmpty()) {
            collectDictionaryForms(readingKey, true, limit, collected);
            if (collected.size() >= limit) return;
        }

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

    /**
     * Tìm theo nghĩa (tiếng Việt, hoặc tiếng Anh với dữ liệu cũ), đúng dấu: âm Hán Việt của từ ->
     * gloss trùng khớp -> gloss bắt đầu bằng query -> gloss chứa query ở đầu một từ (quét toàn bảng,
     * chỉ chạy khi {@code includeContains}). Cột meaning không cần tier riêng: với từ điển nó là vài
     * gloss đầu, với giáo trình gloss tiếng Việt cũng đã được lưu.
     */
    private void collectMeaningModeCandidates(String query, int limit,
                                              LinkedHashMap<Long, VocabularyEntry> collected,
                                              boolean includeContains) {
        String likeQuery = escapeLike(query);
        addAll(collected, vocabularyEntryRepository.findByHanViet(
                query, PageRequest.of(0, remaining(collected, limit))));
        if (collected.size() >= limit) return;

        addAllById(collected, vocabularyGlossRepository.findVocabularyIdsByGlossExact(
                query, PageRequest.of(0, remaining(collected, limit))));
        if (collected.size() >= limit) return;

        addAllById(collected, vocabularyGlossRepository.findVocabularyIdsByGlossPrefix(
                likeQuery, PageRequest.of(0, remaining(collected, limit))));
        if (collected.size() >= limit) return;

        if (!includeContains) return;

        addAllById(collected, vocabularyGlossRepository.findVocabularyIdsByGlossWord(
                likeQuery, PageRequest.of(0, remaining(collected, limit))));
    }

    /** Escape ký tự đại diện của LIKE (escape char mặc định của MySQL là dấu gạch chéo ngược). */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void addAll(LinkedHashMap<Long, VocabularyEntry> collected, List<VocabularyEntry> entries) {
        for (VocabularyEntry entry : entries) {
            collected.putIfAbsent(entry.getId(), entry);
        }
    }

    /** Load entry theo lô và giữ đúng thứ tự xếp hạng của danh sách id. */
    private void addAllById(LinkedHashMap<Long, VocabularyEntry> collected, List<Long> ids) {
        List<Long> missing = ids.stream().filter(id -> !collected.containsKey(id)).toList();
        if (missing.isEmpty()) return;
        Map<Long, VocabularyEntry> byId = new HashMap<>();
        for (VocabularyEntry entry : vocabularyEntryRepository.findByIdIn(missing)) {
            byId.put(entry.getId(), entry);
        }
        for (Long id : missing) {
            VocabularyEntry entry = byId.get(id);
            if (entry != null) collected.putIfAbsent(id, entry);
        }
    }

    private static int remaining(LinkedHashMap<Long, VocabularyEntry> collected, int limit) {
        return Math.max(0, limit - collected.size());
    }

    // -------------------------------------------------------------------
    // Kanji results (chỉ khi mode == KANJI)
    // -------------------------------------------------------------------

    private List<KanjiResponse> findKanjiResults(String normalizedQuery, DictionaryUtil.SearchMode mode) {
        if (mode == DictionaryUtil.SearchMode.VIETNAMESE || mode == DictionaryUtil.SearchMode.ROMAJI) {
            return findKanjiByHanViet(normalizedQuery);
        }
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

    /** Một âm tiết Hán Việt ("học", "thực") -> các Kanji có âm đó. */
    private List<KanjiResponse> findKanjiByHanViet(String normalizedQuery) {
        if (normalizedQuery.contains(" ") || normalizedQuery.contains(",")) return List.of();
        List<Kanji> kanjis = kanjiRepository.findByHanVietReading(
                escapeLike(normalizedQuery), PageRequest.of(0, KANJI_RESULT_LIMIT));
        Map<Long, List<KanjiReadingResponse>> readingsByKanjiId =
                groupReadings(kanjis.stream().map(Kanji::getId).toList());
        return kanjis.stream()
                .map(kanji -> mapper.toKanjiResponse(kanji,
                        readingsByKanjiId.getOrDefault(kanji.getId(), List.of())))
                .toList();
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
            Set<String> preferredLanguage = preferredGlossLanguage(vocabSenses.getValue(), glossesBySense);
            List<SenseResponse> responses = new ArrayList<>();
            for (VocabularySense sense : vocabSenses.getValue()) {
                List<GlossResponse> allGlosses = glossesBySense.getOrDefault(sense.getId(), List.of());
                List<GlossResponse> glosses2 = preferredLanguage == null ? allGlosses
                        : allGlosses.stream().filter(g -> preferredLanguage.contains(g.getLanguageCode())).toList();
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
    private static Set<String> preferredGlossLanguage(List<VocabularySense> vocabSenses,
                                                        Map<Long, List<GlossResponse>> glossesBySense) {
        for (Set<String> language : PREFERRED_GLOSS_LANGUAGES) {
            boolean present = vocabSenses.stream().anyMatch(sense ->
                    glossesBySense.getOrDefault(sense.getId(), List.of()).stream()
                            .anyMatch(gloss -> language.contains(gloss.getLanguageCode())));
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
