package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Response chứa thông tin từ vựng. */
@Data
@Builder
public class VocabularyResponse {
    private Long id;
    private String slug;
    private String reading;
    private String jlptLevel;
    private List<String> glosses;
    // TODO: Bổ sung thêm fields
}
