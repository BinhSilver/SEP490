package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Response chứa thông tin Kanji. */
@Data
@Builder
public class KanjiResponse {
    private Long id;
    private String character;
    private String meaning;
    private String jlptLevel;
    // TODO: Bổ sung thêm fields
}
