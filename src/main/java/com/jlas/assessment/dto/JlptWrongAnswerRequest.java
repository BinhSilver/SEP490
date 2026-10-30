package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record JlptWrongAnswerRequest(@NotNull @Min(1) Long questionId,
                                     @NotNull @Min(1) @Max(4) Integer selectedOption) { }
