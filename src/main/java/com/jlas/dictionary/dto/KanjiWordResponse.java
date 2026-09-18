package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Từ vựng trong bài có chứa một Kanji (bản rút gọn, không kèm audio). */
@Data
@Builder
public class KanjiWordResponse {
    private Long vocabularyId;
    private String expression;
    private String reading;
    private String meaning;
    /** Vị trí (1-based) của chữ Kanji trong từ. */
    private Short characterPosition;
}
