package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record JlptExplanationRequest(
        @Min(1) long questionId,
        @Min(1) @Max(4) Integer selectedChoice) {
}
