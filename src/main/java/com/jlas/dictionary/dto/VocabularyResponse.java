package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Response chứa thông tin từ vựng. */
@Data
@Builder
public class VocabularyResponse {
    private Long id;
    private String expression;
    private String reading;
    private String readingRomaji;
    /** Nghĩa tóm tắt cho nội dung giáo trình; có thể null với vocabulary thuần DICTIONARY. */
    private String meaning;
    private String wordType;
    private String jlptLevel;
    private String exampleJa;
    private String exampleVi;
    /** TEXTBOOK/DICTIONARY/BOTH. */
    private String usageScope;
    /** Danh sách nghĩa theo sense, mỗi sense gộp mọi gloss của nó (không trả record trùng). */
    private List<SenseResponse> senses;
    /** Các Kanji cấu thành expression, theo đúng vị trí ký tự. */
    private List<KanjiCompositionResponse> kanjiComposition;
}
