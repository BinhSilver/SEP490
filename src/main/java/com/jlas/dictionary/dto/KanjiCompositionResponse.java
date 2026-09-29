package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Một Kanji trong thành phần của một vocabulary, kèm vị trí ký tự (1-based). */
@Data
@Builder
public class KanjiCompositionResponse {
    private int position;
    private String character;
    private String jlptLevel;
}
