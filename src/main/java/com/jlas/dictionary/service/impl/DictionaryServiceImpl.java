package com.jlas.dictionary.service.impl;

import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.ContentAudioResponse;
import com.jlas.dictionary.dto.DialogueChunkResponse;
import com.jlas.dictionary.dto.DialogueLineResponse;
import com.jlas.dictionary.dto.DialogueRoleResponse;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.GrammarPointResponse;
import com.jlas.dictionary.dto.KanjiReadingResponse;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.KanjiWordResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.LessonVocabularyResponse;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.entity.ContentAudio;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.DialogueLineChunk;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.entity.VocabularyKanji;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.DialogueLineChunkRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.dictionary.repository.DialogueRoleRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TagRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.dictionary.repository.UnitRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cài đặt {@link DictionaryService}. */
@Service
@RequiredArgsConstructor
@Transactional
public class DictionaryServiceImpl implements DictionaryService {

    /** owner_type của content_audio trỏ vocabulary_entry.id. */
    private static final String AUDIO_OWNER_VOCABULARY = "VOCABULARY_WORD";
    /** owner_type của content_audio trỏ content_item.id. */
    private static final String AUDIO_OWNER_CONTENT_ITEM = "CONTENT_ITEM";
    /** content_type của content_item là câu ví dụ. */
    private static final String CONTENT_TYPE_EXAMPLE = "GRAMMAR";
    /** content_type của content_item là mục đọc: chữ kana (giáo trình bảng chữ cái) hoặc đoạn chú thích. */
    private static final String CONTENT_TYPE_READING = "READING";
    /** Trạng thái duyệt công khai của textbook / lesson. */
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    /** Authority đầy đủ (CustomUserDetailsService nối tiền tố ROLE_ vào roles.name). */
    private static final String ROLE_CONTENT_CREATOR = "ROLE_CONTENT_CREATOR";
    /** tag.tag_type của chủ đề học. */
    private static final String TAG_TYPE_TOPIC = "SITUATION";

    private final TextbookRepository textbookRepository;
    private final TagRepository tagRepository;
    private final UnitRepository unitRepository;
    private final LessonRepository lessonRepository;
    private final ContentVocabularyRepository contentVocabularyRepository;
    private final VocabularyKanjiRepository vocabularyKanjiRepository;
    private final KanjiReadingRepository kanjiReadingRepository;
    private final ContentItemRepository contentItemRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final DialogueRoleRepository dialogueRoleRepository;
    private final DialogueLineDetailRepository dialogueLineDetailRepository;
    private final DialogueLineChunkRepository dialogueLineChunkRepository;
    private final ContentAudioRepository contentAudioRepository;
    private final DictionaryMapper dictionaryMapper;

    @Override
    @Transactional(readOnly = true)
    public List<TextbookResponse> listTextbooks() {
        List<Textbook> textbooks = canViewDraft()
                ? textbookRepository.findAllByOrderByIdAsc()
                : textbookRepository.findByStatusOrderByIdAsc(STATUS_PUBLISHED);
        if (textbooks.isEmpty()) return Collections.emptyList();

        Map<Long, Long> unitCountByTextbook = new HashMap<>();
        for (Object[] row : textbookRepository.countUnitsByTextbookIds(
                textbooks.stream().map(Textbook::getId).toList())) {
            unitCountByTextbook.put((Long) row[0], (Long) row[1]);
        }
        return textbooks.stream()
                .map(t -> dictionaryMapper.toTextbookResponse(t, unitCountByTextbook.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TextbookUnitsResponse getTextbookUnits(Long textbookId) {
        boolean viewDraft = canViewDraft();
        Textbook textbook = (viewDraft
                ? textbookRepository.findById(textbookId)
                : textbookRepository.findByIdAndStatus(textbookId, STATUS_PUBLISHED))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Textbook not found: " + textbookId));

        List<Unit> units = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId);
        List<Lesson> lessons = viewDraft
                ? lessonRepository.findByTextbookId(textbookId)
                : lessonRepository.findByTextbookIdAndStatus(textbookId, STATUS_PUBLISHED);

        Map<Long, List<LessonSummaryResponse>> lessonsByUnit = new LinkedHashMap<>();
        for (Lesson lesson : lessons) {
            lessonsByUnit.computeIfAbsent(lesson.getUnit().getId(), k -> new ArrayList<>())
                    .add(dictionaryMapper.toLessonSummaryResponse(lesson));
        }

        return TextbookUnitsResponse.builder()
                .textbook(dictionaryMapper.toTextbookResponse(textbook, (long) units.size()))
                .units(units.stream()
                        .map(u -> dictionaryMapper.toUnitResponse(
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
                ? tagRepository.countLessonsByTagType(TAG_TYPE_TOPIC)
                : tagRepository.countLessonsByTagTypeAndStatus(TAG_TYPE_TOPIC, STATUS_PUBLISHED);
        for (Object[] row : rows) countByTag.put((Long) row[0], (Long) row[1]);
        // Chủ đề 0 bài vẫn trả về với lessonCount = 0 (chủ ý, không ẩn).
        return topics.stream()
                .map(t -> dictionaryMapper.toTopicResponse(t, countByTag.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TopicLessonsResponse getTopicLessons(String code) {
        // NOT_FOUND chỉ khi tag không tồn tại; tag tồn tại mà không có bài được xem → 200 với lessons rỗng.
        Tag tag = tagRepository.findByCodeAndTagType(code, TAG_TYPE_TOPIC)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Topic not found: " + code));
        List<Lesson> lessons = canViewDraft()
                ? lessonRepository.findByTagCode(code)
                : lessonRepository.findByTagCodeAndStatus(code, STATUS_PUBLISHED);
        return TopicLessonsResponse.builder()
                .topic(dictionaryMapper.toTopicResponse(tag, (long) lessons.size()))
                .lessons(lessons.stream().map(dictionaryMapper::toTopicLessonResponse).toList())
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
        return dictionaryMapper.toTextbookResponse(textbookRepository.save(textbook), unitCount);
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
        return dictionaryMapper.toLessonSummaryResponse(lessonRepository.save(lesson));
    }

    /**
     * Số query cố định cho một bài (không N+1), 12 câu: lesson, từ vựng, audio từ vựng,
     * liên kết từ↔Kanji (kèm Kanji + bộ thủ), cách đọc Kanji, ngữ pháp, vai, lời thoại,
     * cụm tách nghĩa, câu ví dụ, mục đọc (READING), audio content_item
     * (câu ví dụ + lời thoại gộp trong MỘT lời gọi vì cùng owner_type).
     * Learner bị chặn ở cả hai tầng: lesson DRAFT trong textbook PUBLISHED, và lesson thuộc textbook DRAFT.
     */
    @Override
    @Transactional(readOnly = true)
    public LessonContentResponse getLessonContent(Long lessonId) {
        Lesson lesson = lessonRepository.findWithUnitById(lessonId)
                .filter(l -> canViewDraft() || isPublished(l))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));

        LessonContentResponse response = dictionaryMapper.toLessonContentResponse(lesson);
        List<VocabularyEntry> vocabularies = contentVocabularyRepository.findVocabularyByLessonId(lessonId);
        response.setKanjis(loadKanjis(vocabularies));
        response.setVocabularies(toVocabularies(vocabularies));
        response.setGrammarPoints(loadGrammarPoints(lessonId));
        response.setDialogueRoles(loadDialogueRoles(lessonId));

        // Câu ví dụ, mục đọc (bảng chữ kana / chú thích) và lời thoại đều là content_item
        // → audio lấy chung MỘT lần theo CONTENT_ITEM cho cả ba.
        List<ContentItem> examples = contentItemRepository.findByLessonIdAndContentType(lessonId, CONTENT_TYPE_EXAMPLE);
        List<ContentItem> readings = contentItemRepository.findByLessonIdAndContentType(lessonId, CONTENT_TYPE_READING);
        List<DialogueLineDetail> dialogueLines = dialogueLineDetailRepository.findByLessonId(lessonId);

        List<Long> contentItemIds = new ArrayList<>(examples.size() + readings.size() + dialogueLines.size());
        examples.forEach(ci -> contentItemIds.add(ci.getId()));
        readings.forEach(ci -> contentItemIds.add(ci.getId()));
        dialogueLines.forEach(d -> contentItemIds.add(d.getContentItemId()));
        Map<Long, List<ContentAudioResponse>> audioByItem = loadAudioByOwner(AUDIO_OWNER_CONTENT_ITEM, contentItemIds);

        response.setExampleSentences(toExampleSentences(examples, audioByItem));
        response.setReadingItems(toExampleSentences(readings, audioByItem));
        response.setDialogueLines(toDialogueLines(dialogueLines, audioByItem));
        return response;
    }

    /**
     * Kanji xuất hiện trong từ vựng của bài (content_vocabulary → vocabulary_kanji → kanji), 2 query:
     * liên kết từ↔Kanji kèm Kanji + bộ thủ, rồi cách đọc On/Kun theo tập kanji_id.
     * Dữ liệu Kanji từ KANJIDIC2 (EDRDG, CC BY-SA 4.0).
     */
    private List<KanjiResponse> loadKanjis(List<VocabularyEntry> entries) {
        if (entries.isEmpty()) return Collections.emptyList();
        Map<Long, VocabularyEntry> entryById = new LinkedHashMap<>();
        entries.forEach(v -> entryById.put(v.getId(), v));

        // Kanji theo id (giữ thứ tự xuất hiện) + các từ của bài chứa từng chữ.
        Map<Long, Kanji> kanjiById = new LinkedHashMap<>();
        Map<Long, List<KanjiWordResponse>> wordsByKanji = new LinkedHashMap<>();
        for (VocabularyKanji link : vocabularyKanjiRepository.findByVocabularyIdIn(entryById.keySet())) {
            Kanji kanji = link.getKanji();
            kanjiById.putIfAbsent(kanji.getId(), kanji);
            wordsByKanji.computeIfAbsent(kanji.getId(), k -> new ArrayList<>())
                    .add(dictionaryMapper.toKanjiWordResponse(
                            entryById.get(link.getId().getVocabularyId()), link.getId().getCharacterPosition()));
        }
        if (kanjiById.isEmpty()) return Collections.emptyList();

        Map<Long, List<KanjiReadingResponse>> readingsByKanji = new LinkedHashMap<>();
        for (KanjiReading reading : kanjiReadingRepository
                .findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(kanjiById.keySet())) {
            readingsByKanji.computeIfAbsent(reading.getKanji().getId(), k -> new ArrayList<>())
                    .add(dictionaryMapper.toKanjiReadingResponse(reading));
        }

        return kanjiById.values().stream()
                .map(k -> dictionaryMapper.toKanjiResponse(
                        k,
                        readingsByKanji.getOrDefault(k.getId(), Collections.emptyList()),
                        wordsByKanji.getOrDefault(k.getId(), Collections.emptyList())))
                .toList();
    }

    private List<LessonVocabularyResponse> toVocabularies(List<VocabularyEntry> entries) {
        Map<Long, List<ContentAudioResponse>> audioByOwner = loadAudioByOwner(
                AUDIO_OWNER_VOCABULARY, entries.stream().map(VocabularyEntry::getId).toList());
        return entries.stream()
                .map(v -> dictionaryMapper.toLessonVocabularyResponse(
                        v, audioByOwner.getOrDefault(v.getId(), Collections.emptyList())))
                .toList();
    }

    private List<GrammarPointResponse> loadGrammarPoints(Long lessonId) {
        return grammarPointRepository.findByLessonIdOrderByOrderIndexAsc(lessonId).stream()
                .map(dictionaryMapper::toGrammarPointResponse)
                .toList();
    }

    private List<DialogueRoleResponse> loadDialogueRoles(Long lessonId) {
        return dialogueRoleRepository.findByLessonId(lessonId).stream()
                .map(dictionaryMapper::toDialogueRoleResponse)
                .toList();
    }

    private List<ExampleSentenceResponse> toExampleSentences(List<ContentItem> examples,
                                                             Map<Long, List<ContentAudioResponse>> audioByItem) {
        return examples.stream()
                .map(ci -> dictionaryMapper.toExampleSentenceResponse(
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
                    .add(dictionaryMapper.toDialogueChunkResponse(chunk));
        }

        return details.stream()
                .map(d -> dictionaryMapper.toDialogueLineResponse(
                        d,
                        chunksByItem.getOrDefault(d.getContentItemId(), Collections.emptyList()),
                        audioByItem.getOrDefault(d.getContentItemId(), Collections.emptyList())))
                .toList();
    }

    /** CONTENT_CREATOR thấy mọi trạng thái; mọi vai khác (kể cả ADMIN, theo quyết định team) và người chưa đăng nhập chỉ thấy PUBLISHED. */
    private static boolean canViewDraft() {
        return CurrentUser.hasRole(ROLE_CONTENT_CREATOR);
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
                    .add(dictionaryMapper.toContentAudioResponse(audio));
        }
        return result;
    }
}
