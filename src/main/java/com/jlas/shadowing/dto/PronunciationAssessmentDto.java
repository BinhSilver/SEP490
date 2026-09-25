package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

@Data
public class PronunciationAssessmentDto {
    private Long attemptId;
    private Long subtitleId;
    private String audioUrl;
    private Long audioSizeBytes;
    private LocalDateTime createdAt;
    private String recognitionStatus;
    private String referenceText;
    private String recognizedText;
    private Double pronunciationScore;
    private Double accuracyScore;
    private Double fluencyScore;
    private Double completenessScore;
    private Double prosodyScore;
    private String feedback;
    private List<PronunciationWordDto> words = new ArrayList<>();
}
