package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Response chứa một diễn giải nghĩa theo ngôn ngữ. */
@Data
@Builder
public class GlossResponse {
    private String languageCode;
    private String text;
}
