package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Nội dung đầy đủ một bài học: Kanji xuất hiện trong từ vựng, từ vựng kèm audio,
 * câu ví dụ kèm audio, mẫu ngữ pháp, hội thoại kèm vai và cụm tách nghĩa.
 */
@Data
@Builder
public class LessonContentResponse {
    private Long id;
    private String title;
    private String description;
    private String lessonType;
    private String skill;
    private String status;
    private Integer orderIndex;
    private Integer estimatedMinutes;
    private Long unitId;
    private String unitTitle;
    private String knowledgeTagCode;
    private String knowledgeTagName;
    private List<KanjiResponse> kanjis;
    private List<LessonVocabularyResponse> vocabularies;
    private List<ExampleSentenceResponse> exampleSentences;
    /**
     * Mục đọc (content_item READING): với giáo trình bảng chữ cái là từng chữ kana (textJa = kana,
     * textVi = romaji); với bài N5/N4 là đoạn chú thích. Dùng chung DTO câu ví dụ vì cùng cấu trúc.
     */
    private List<ExampleSentenceResponse> readingItems;
    private List<GrammarPointResponse> grammarPoints;
    private List<DialogueRoleResponse> dialogueRoles;
    private List<DialogueLineResponse> dialogueLines;
}
