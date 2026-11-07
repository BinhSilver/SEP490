package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

@Data
public class PronunciationAssessmentDto {
    private Long attemptId;
    /** CP trừ cho lần chấm này (0 khi còn lượt miễn phí), để trang ghi phí và đếm mức tự dùng trong ngày. */
    private int creditCharged;
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
