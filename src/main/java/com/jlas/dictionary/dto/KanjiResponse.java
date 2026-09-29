package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response chứa thông tin Kanji. */
@Data
@Builder
public class KanjiResponse {
    private Long id;
    private String character;
    private String unicodeCode;
    private Integer strokeCount;
    private String hanViet;
    private String meaningVi;
    private String jlptLevel;
    private String strokeSvgUri;
    private RadicalResponse radical;
    private List<KanjiReadingResponse> readings;
    /** Chỉ populate ở trang chi tiết Kanji, để trống trong kết quả search để tránh query thừa. */
    private List<VocabularyResponse> relatedVocabulary;
}
