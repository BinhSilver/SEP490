package com.jlas.shadowing.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PronunciationAttempt {
    private Long id;
    private Long userId;
    private Long videoId;
    private Long subtitleId;
    private String storageKey;
    private String audioContentType;
    private Long audioSizeBytes;
    private Double audioDurationSeconds;
    private String recognitionStatus;
    private String referenceText;
    private String recognizedText;
    private Double pronunciationScore;
    private Double accuracyScore;
    private Double fluencyScore;
    private Double completenessScore;
    private Double prosodyScore;
    private String feedback;
    private LocalDateTime createdAt;
    private List<PronunciationWordResult> words = new ArrayList<>();
}
