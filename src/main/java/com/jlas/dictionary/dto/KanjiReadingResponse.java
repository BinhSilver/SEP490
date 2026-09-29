package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Response chứa một cách đọc của Kanji (ON/KUN/NANORI/OTHER). */
@Data
@Builder
public class KanjiReadingResponse {
    private String readingType;
    private String readingText;
    private boolean common;
}
