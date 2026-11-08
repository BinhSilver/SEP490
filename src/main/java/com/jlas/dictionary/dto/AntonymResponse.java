package com.jlas.dictionary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AntonymResponse {
    private String expression;
    private String reading;
    private Long vocabularyId;
    private String meaning;
}
