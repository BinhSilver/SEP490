package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Mẫu ngữ pháp của một bài học. */
@Data
@Builder
public class GrammarPointResponse {
    private Long id;
    private String pattern;
    private String meaning;
    private String example;
    private String note;
    private Integer orderIndex;
}
