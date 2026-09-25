package com.jlas.shadowing.model;

import lombok.Data;

@Data
public class PronunciationPhonemeResult {
    private Long id;
    private Long wordResultId;
    private Integer position;
    private String phoneme;
    private Double accuracyScore;
}
