package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record JlptAdminExamRequest(
        @NotBlank @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String code,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "N[1-5]") String level,
        @Min(2000) @Max(2100) Integer year,
        @Min(7) @Max(12) Integer month,
        @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|ARCHIVED") String status,
        @Min(0) @Max(180) Integer passingScore) {
}
