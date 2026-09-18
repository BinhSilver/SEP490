package com.jlas.dictionary.mapper;

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
import com.jlas.dictionary.dto.TopicLessonResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.dto.UnitResponse;
import com.jlas.dictionary.entity.ContentAudio;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.ContentVoice;
import com.jlas.dictionary.entity.DialogueLineChunk;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.dictionary.entity.DialogueRole;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Radical;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Mapper chuyển đổi giữa Dictionary entities và DTO. */
@Component
public class DictionaryMapper {

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

    /** Kanji kèm bộ thủ (yêu cầu đã fetch join radical); readings/words do service gắn. */
    public KanjiResponse toKanjiResponse(Kanji kanji, List<KanjiReadingResponse> readings, List<KanjiWordResponse> words) {
        if (kanji == null) return null;
        Radical radical = kanji.getRadical();
        return KanjiResponse.builder()
                .id(kanji.getId())
                .character(kanji.getCharacter())
                .unicodeCode(kanji.getUnicodeCode())
                .meaning(kanji.getMeaningVi())
                .hanViet(kanji.getHanViet())
                .strokeCount(kanji.getStrokeCount())
                .jlptLevel(kanji.getJlptLevel())
                .radicalNumber(radical != null ? radical.getRadicalNumber() : null)
                .radicalCharacter(radical != null ? radical.getCharacter() : null)
                .radicalNameVi(radical != null ? radical.getNameVi() : null)
                .readings(readings)
                .words(words)
                .build();
    }

    public KanjiReadingResponse toKanjiReadingResponse(KanjiReading reading) {
        if (reading == null) return null;
        return KanjiReadingResponse.builder()
                .readingType(reading.getReadingType())
                .readingText(reading.getReadingText())
                .isCommon(reading.getIsCommon()) // gợi ý thứ tự học, không phải tần suất
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
}
