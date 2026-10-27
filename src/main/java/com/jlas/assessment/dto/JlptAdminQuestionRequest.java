package com.jlas.assessment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record JlptAdminQuestionRequest(
        @NotNull Long mondaiId,
        @Min(1) int number,
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,50}") String type,
        @NotBlank String prompt,
        String passage,
        @NotNull @Size(min = 2, max = 4) List<String> choices,
        @Min(1) @Max(4) Integer correctChoice,
        @DecimalMin("0.0") BigDecimal score,
        @Size(max = 1000) String imageUrl,
        @Min(1) Integer sourcePage,
        String explanation,
        String scriptText,
        String scriptTranslation,
        @Size(max = 1000) String audioUrl,
        @Size(min = 4, max = 4) List<@NotNull @Min(1) @Max(4) Integer> correctOrder,
        @Min(1) Long targetVocabularyId) {
}
