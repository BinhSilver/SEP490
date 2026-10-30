package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Từ vựng của một bài học kèm audio 6 giọng. */
@Data
@Builder
public class LessonVocabularyResponse {
    private Long id;
    private String expression;
    private String reading;
    private String readingRomaji;
    private String meaning;
    private String wordType;
    private String jlptLevel;
    /** Ví dụ chung của từ trong từ điển (vocabulary_entry.example_ja) — dùng khi bài không có ví dụ riêng. */
    private String exampleJa;
    private String exampleVi;
    private List<ContentAudioResponse> audios;
    /** Câu ví dụ của chính bài này cho từ (content_item GRAMMAR nối với từ qua content_vocabulary); rỗng nếu không có. */
    private List<ExampleSentenceResponse> lessonExamples;
}
