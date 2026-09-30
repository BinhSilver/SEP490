package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response chứa một nghĩa/ngữ cảnh nghĩa của một vocabulary, gộp mọi gloss cùng sense. */
@Data
@Builder
public class SenseResponse {
    private Integer senseOrder;
    private String usageNote;
    private List<String> partsOfSpeech;
    private List<String> domains;
    private List<GlossResponse> glosses;
}
