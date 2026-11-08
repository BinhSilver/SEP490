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
    private Integer pitchAccent;
    private String pitchAccentSource;
    /** Âm Hán Việt của cả từ; null nếu nguồn không có. */
    private String hanViet;
    /** Nghĩa tóm tắt (giáo trình hoặc vài nghĩa đầu của từ điển Nhật-Việt). */
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
    private List<AntonymResponse> antonyms;
}
