package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class PronunciationWordDto {
    private String word;
    private Double accuracyScore;
    private String errorType;
    private List<PronunciationPhonemeDto> phonemes = new ArrayList<>();
}
