package com.jlas.dictionary.mapper;

import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper chuyển đổi giữa Dictionary entities và DTO.
 * Chỉ map entity đơn lẻ -> DTO; việc gom nhóm sense/gloss/kanji-composition theo lô
 * (để tránh N+1) thuộc trách nhiệm của DictionaryServiceImpl.
 */
@Component
public class DictionaryMapper {

    // =====================================================================
    // Duyệt giáo trình đã số hoá (textbook / unit / topic / lesson content)
    // =====================================================================

    public TextbookResponse toTextbookResponse(Textbook textbook, Long unitCount) {
        if (textbook == null) return null;
        return TextbookResponse.builder()
                .id(textbook.getId())
                .title(textbook.getTitle())
                .description(textbook.getDescription())
                .jlptLevel(textbook.getJlptLevel())
                .status(textbook.getStatus())
                .version(textbook.getVersion())
                .unitCount(unitCount)
                .build();
    }

    /** Unit kèm danh sách bài học đã lọc theo quyền xem (service truyền vào). */
    public UnitResponse toUnitResponse(Unit unit, List<LessonSummaryResponse> lessons) {
        if (unit == null) return null;
        return UnitResponse.builder()
                .id(unit.getId())
                .title(unit.getTitle())
                .description(unit.getDescription())
                .orderIndex(unit.getOrderIndex())
                .lessons(lessons)
                .build();
    }

    public TopicResponse toTopicResponse(Tag tag, Long lessonCount) {
        if (tag == null) return null;
        return TopicResponse.builder()
                .id(tag.getId())
                .code(tag.getCode())
                .name(tag.getName())
                .lessonCount(lessonCount)
                .build();
    }

    /** Bài trong chủ đề kèm ngữ cảnh giáo trình; yêu cầu lesson đã fetch join unit và unit.textbook. */
    public TopicLessonResponse toTopicLessonResponse(Lesson lesson) {
        if (lesson == null) return null;
        Unit unit = lesson.getUnit();
        Textbook textbook = unit != null ? unit.getTextbook() : null;
        return TopicLessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .description(lesson.getDescription())
                .lessonType(lesson.getLessonType())
                .skill(lesson.getSkill())
                .status(lesson.getStatus())
                .orderIndex(lesson.getOrderIndex())
                .estimatedMinutes(lesson.getEstimatedMinutes())
                .unitId(unit != null ? unit.getId() : null)
                .unitTitle(unit != null ? unit.getTitle() : null)
                .textbookId(textbook != null ? textbook.getId() : null)
                .textbookTitle(textbook != null ? textbook.getTitle() : null)
                .jlptLevel(textbook != null ? textbook.getJlptLevel() : null)
                .build();
    }

    public LessonSummaryResponse toLessonSummaryResponse(Lesson lesson) {
        if (lesson == null) return null;
        return LessonSummaryResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .description(lesson.getDescription())
                .lessonType(lesson.getLessonType())
                .skill(lesson.getSkill())
                .status(lesson.getStatus())
                .orderIndex(lesson.getOrderIndex())
                .estimatedMinutes(lesson.getEstimatedMinutes())
                .build();
    }

    /**
     * Header nội dung bài học. Các danh sách con (từ vựng, ngữ pháp, hội thoại) do service gắn.
     * Yêu cầu lesson đã fetch join unit và knowledgeTag.
     */
    public LessonContentResponse toLessonContentResponse(Lesson lesson) {
        if (lesson == null) return null;
        return LessonContentResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .description(lesson.getDescription())
                .lessonType(lesson.getLessonType())
                .skill(lesson.getSkill())
                .status(lesson.getStatus())
                .orderIndex(lesson.getOrderIndex())
                .estimatedMinutes(lesson.getEstimatedMinutes())
                .unitId(lesson.getUnit() != null ? lesson.getUnit().getId() : null)
                .unitTitle(lesson.getUnit() != null ? lesson.getUnit().getTitle() : null)
                .knowledgeTagCode(lesson.getKnowledgeTag() != null ? lesson.getKnowledgeTag().getCode() : null)
                .knowledgeTagName(lesson.getKnowledgeTag() != null ? lesson.getKnowledgeTag().getName() : null)
                .build();
    }

    /** Kanji trong bài học giáo trình, kèm bộ thủ phẳng (yêu cầu đã fetch join radical); readings/words do service gắn. */
    public KanjiResponse toKanjiResponse(Kanji kanji, List<KanjiReadingResponse> readings, List<KanjiWordResponse> words) {
        if (kanji == null) return null;
        Radical radical = kanji.getRadical();
        return KanjiResponse.builder()
                .id(kanji.getId())
                .character(kanji.getCharacter())
                .unicodeCode(kanji.getUnicodeCode())
                .meaning(kanji.getMeaningVi())
                .hanViet(kanji.getHanViet())
                .strokeCount(kanji.getStrokeCount() == null ? null : kanji.getStrokeCount().intValue())
                .jlptLevel(kanji.getJlptLevel())
                .radicalNumber(radical != null ? radical.getRadicalNumber() : null)
                .radicalCharacter(radical != null ? radical.getCharacter() : null)
                .radicalNameVi(radical != null ? radical.getNameVi() : null)
                .readings(readings)
                .words(words)
                .build();
    }

    /** Từ trong bài chứa Kanji; entry lấy từ danh sách từ vựng đã tải của bài. */
    public KanjiWordResponse toKanjiWordResponse(VocabularyEntry entry, Short characterPosition) {
        if (entry == null) return null;
        return KanjiWordResponse.builder()
                .vocabularyId(entry.getId())
                .expression(entry.getExpression())
                .reading(entry.getReading())
                .meaning(entry.getMeaning())
                .characterPosition(characterPosition)
                .build();
    }

    public LessonVocabularyResponse toLessonVocabularyResponse(VocabularyEntry entry, List<ContentAudioResponse> audios) {
        if (entry == null) return null;
        return LessonVocabularyResponse.builder()
                .id(entry.getId())
                .expression(entry.getExpression())
                .reading(entry.getReading())
                .readingRomaji(entry.getReadingRomaji())
                .meaning(entry.getMeaning())
                .wordType(entry.getWordType())
                .jlptLevel(entry.getJlptLevel())
                .exampleJa(entry.getExampleJa())
                .exampleVi(entry.getExampleVi())
                .audios(audios)
                .build();
    }

    /** Yêu cầu audio đã fetch join voice. */
    public ContentAudioResponse toContentAudioResponse(ContentAudio audio) {
        if (audio == null) return null;
        ContentVoice voice = audio.getVoice();
        return ContentAudioResponse.builder()
                .id(audio.getId())
                .voiceCode(voice != null ? voice.getCode() : null)
                .voiceName(voice != null ? voice.getName() : null)
                .voiceGender(voice != null ? voice.getGender() : null)
                .voiceDefault(voice != null ? voice.getIsDefault() : null)
                .audioUrl(audio.getAudioUrl())
                .mergedUrl(audio.getMergedUrl())
                .startMs(audio.getStartMs())
                .endMs(audio.getEndMs())
                .build();
    }

    /** Câu ví dụ (content_item GRAMMAR). Yêu cầu item đã fetch join section. */
    public ExampleSentenceResponse toExampleSentenceResponse(ContentItem item, List<ContentAudioResponse> audios) {
        if (item == null) return null;
        return ExampleSentenceResponse.builder()
                .contentItemId(item.getId())
                .sectionId(item.getSection() != null ? item.getSection().getId() : null)
                .sectionTitle(item.getSection() != null ? item.getSection().getTitle() : null)
                .title(item.getTitle())
                .textJa(item.getContent())
                .textVi(item.getTranslatedText())
                .orderIndex(item.getOrderIndex())
                .grammarPointId(item.getGrammarPointId())
                .audios(audios)
                .build();
    }

    public GrammarPointResponse toGrammarPointResponse(GrammarPoint point) {
        if (point == null) return null;
        return GrammarPointResponse.builder()
                .id(point.getId())
                .pattern(point.getPattern())
                .meaning(point.getMeaning())
                .example(point.getExample())
                .note(point.getNote())
                .orderIndex(point.getOrderIndex())
                .build();
    }

    /** Yêu cầu role đã fetch join voice. */
    public DialogueRoleResponse toDialogueRoleResponse(DialogueRole role) {
        if (role == null) return null;
        return DialogueRoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .nameVi(role.getNameVi())
                .nameJa(role.getNameJa())
                .description(role.getDescription())
                .voiceCode(role.getVoice() != null ? role.getVoice().getCode() : null)
                .build();
    }

    public DialogueChunkResponse toDialogueChunkResponse(DialogueLineChunk chunk) {
        if (chunk == null) return null;
        return DialogueChunkResponse.builder()
                .id(chunk.getId())
                .orderIndex(chunk.getOrderIndex())
                .surface(chunk.getSurface())
                .meaningVi(chunk.getMeaningVi())
                .build();
    }

    /** Yêu cầu detail đã fetch join contentItem, section và role. */
    public DialogueLineResponse toDialogueLineResponse(DialogueLineDetail detail,
                                                       List<DialogueChunkResponse> chunks,
                                                       List<ContentAudioResponse> audios) {
        if (detail == null) return null;
        ContentItem item = detail.getContentItem();
        DialogueRole role = detail.getRole();
        return DialogueLineResponse.builder()
                .contentItemId(detail.getContentItemId())
                .sectionId(item.getSection() != null ? item.getSection().getId() : null)
                .sectionTitle(item.getSection() != null ? item.getSection().getTitle() : null)
                .orderIndex(item.getOrderIndex())
                .text(item.getContent())
                .translatedText(item.getTranslatedText())
                .roleId(role != null ? role.getId() : null)
                .roleCode(role != null ? role.getCode() : null)
                .roleNameVi(role != null ? role.getNameVi() : null)
                .sceneCode(detail.getSceneCode())
                .bubbleSide(detail.getBubbleSide())
                .bubbleX(detail.getBubbleX())
                .bubbleY(detail.getBubbleY())
                .chunks(chunks)
                .audios(audios)
                .build();
    }

    // =====================================================================
    // Tra cứu từ điển (search / vocabulary / kanji detail / history)
    // =====================================================================

    /** Kanji cho trang tra từ điển, kèm bộ thủ lồng và SVG thứ tự nét. */
    public KanjiResponse toKanjiResponse(Kanji kanji, List<KanjiReadingResponse> readings) {
        return KanjiResponse.builder()
                .id(kanji.getId())
                .character(kanji.getCharacter())
                .unicodeCode(kanji.getUnicodeCode())
                .strokeCount(kanji.getStrokeCount() == null ? null : kanji.getStrokeCount().intValue())
                .hanViet(kanji.getHanViet())
                .meaningVi(kanji.getMeaningVi())
                .jlptLevel(kanji.getJlptLevel())
                .strokeSvgUri(kanji.getStrokeSvgUri())
                .radical(toRadicalResponse(kanji.getRadical()))
                .readings(readings == null ? List.of() : readings)
                .build();
    }

    public RadicalResponse toRadicalResponse(Radical radical) {
        if (radical == null) return null;
        return RadicalResponse.builder()
                .radicalNumber(radical.getRadicalNumber() == null ? null : radical.getRadicalNumber().intValue())
                .character(radical.getCharacter())
                .nameJa(radical.getNameJa())
                .nameVi(radical.getNameVi())
                .meaningVi(radical.getMeaningVi())
                .strokeCount(radical.getStrokeCount() == null ? null : radical.getStrokeCount().intValue())
                .build();
    }

    /** Dùng chung cho cả hai trang: gán cả {@code isCommon} (giáo trình) lẫn {@code common} (từ điển). */
    public KanjiReadingResponse toKanjiReadingResponse(KanjiReading reading) {
        if (reading == null) return null;
        return KanjiReadingResponse.builder()
                .readingType(reading.getReadingType())
                .readingText(reading.getReadingText())
                .isCommon(reading.getIsCommon()) // gợi ý thứ tự học, không phải tần suất
                .common(Boolean.TRUE.equals(reading.getIsCommon()))
                .build();
    }

    public VocabularyResponse toVocabularyResponse(VocabularyEntry entry,
                                                     List<SenseResponse> senses,
                                                     List<KanjiCompositionResponse> composition) {
        return VocabularyResponse.builder()
                .id(entry.getId())
                .expression(entry.getExpression())
                .reading(entry.getReading())
                .readingRomaji(entry.getReadingRomaji())
                .meaning(entry.getMeaning())
                .wordType(entry.getWordType())
                .jlptLevel(entry.getJlptLevel())
                .exampleJa(entry.getExampleJa())
                .exampleVi(entry.getExampleVi())
                .usageScope(entry.getUsageScope())
                .senses(senses == null ? List.of() : senses)
                .kanjiComposition(composition == null ? List.of() : composition)
                .build();
    }

    public SenseResponse toSenseResponse(VocabularySense sense, List<String> partsOfSpeech,
                                          List<String> domains, List<GlossResponse> glosses) {
        return SenseResponse.builder()
                .senseOrder(sense.getSenseOrder() == null ? null : sense.getSenseOrder().intValue())
                .usageNote(sense.getUsageNote())
                .partsOfSpeech(partsOfSpeech == null ? List.of() : partsOfSpeech)
                .domains(domains == null ? List.of() : domains)
                .glosses(glosses == null ? List.of() : glosses)
                .build();
    }

    public GlossResponse toGlossResponse(VocabularyGloss gloss) {
        return GlossResponse.builder()
                .languageCode(gloss.getLanguageCode())
                .text(gloss.getGlossText())
                .build();
    }

    public KanjiCompositionResponse toKanjiCompositionResponse(VocabularyKanji vocabularyKanji) {
        return KanjiCompositionResponse.builder()
                .position(vocabularyKanji.getId().getCharacterPosition())
                .character(vocabularyKanji.getKanji().getCharacter())
                .jlptLevel(vocabularyKanji.getKanji().getJlptLevel())
                .build();
    }

    public SearchHistoryResponse toHistoryResponse(DictionarySearchHistory history) {
        return SearchHistoryResponse.builder()
                .id(history.getId())
                .queryText(history.getQueryText())
                .searchMode(history.getSearchMode())
                .resultCount(history.getResultCount())
                .searchedAt(history.getSearchedAt())
                .selectedVocabularyId(history.getSelectedVocabulary() == null
                        ? null : history.getSelectedVocabulary().getId())
                .build();
    }
}
