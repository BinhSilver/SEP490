package com.jlas.shadowing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PronunciationPhonemeDto {
    private String phoneme;
    private Double accuracyScore;
}
