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
    private String exampleJa;
    private String exampleVi;
    private List<ContentAudioResponse> audios;
}
