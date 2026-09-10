package com.jlas.shadowing.model.practice;

import lombok.Data;

@Data
public class ShadowingClozeCandidate {
    private Long id;
    private Long analysisId;
    private int candidateOrder;
    private String answerText;
    private String readingText;
    private int occurrenceIndex = 1;
    private String candidateType;
    private String jlptLevel;
    private int importanceFactor;
    private int grammarComplexityFactor;
    private int contextPredictabilityFactor;
    private int acousticConfusabilityFactor;
    private int confidenceFactor;
    private int difficultyScore;
    private String generationSource;
}
