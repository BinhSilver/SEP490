package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SmartTestAnswerRequest(
        @NotNull Long questionId,
        /** null = bỏ qua câu (hết giờ), không bị chấm đúng/sai. */
        @Min(1) @Max(4) Integer selectedOption
) { }
