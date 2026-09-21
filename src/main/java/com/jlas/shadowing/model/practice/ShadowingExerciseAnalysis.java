package com.jlas.shadowing.model.practice;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ShadowingExerciseAnalysis {
    private Long id;
    private Long subtitleId;
    private String sourceHash;
    private String promptVersion;
    private int vocabularyFactor;
    private int grammarFactor;
    private int listeningSpeedFactor;
    private int scriptComplexityFactor;
    private int listeningTrapsFactor;
    private int sentenceDifficulty;
    private String analysisSource;
    private LocalDateTime analyzedAt;
    private List<ShadowingClozeCandidate> candidates = new ArrayList<>();
}
