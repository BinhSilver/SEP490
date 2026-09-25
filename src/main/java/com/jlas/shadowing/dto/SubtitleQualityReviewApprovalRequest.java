package com.jlas.shadowing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubtitleQualityReviewApprovalRequest(
        @NotBlank(message = "Nội dung câu không được để trống")
        @Size(max = 5000, message = "Nội dung câu quá dài")
        String text) {
}
