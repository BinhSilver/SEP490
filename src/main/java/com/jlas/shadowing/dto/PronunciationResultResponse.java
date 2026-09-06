package com.jlas.shadowing.dto;

import lombok.Builder;
import lombok.Data;

/** Response kết quả đánh giá phát âm. */
@Data
@Builder
public class PronunciationResultResponse {
    private Long attemptId;
    private Double overallScore;
    private String feedback;
}
