package com.jlas.shadowing.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PronunciationWordResult {
    private Long id;
    private Long attemptId;
    private Integer position;
    private String word;
    private Double accuracyScore;
    private String errorType;
    private List<PronunciationPhonemeResult> phonemes = new ArrayList<>();
}
