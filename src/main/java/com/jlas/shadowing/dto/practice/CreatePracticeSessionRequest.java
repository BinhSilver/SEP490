package com.jlas.shadowing.dto.practice;

import com.jlas.shadowing.model.practice.PracticeMode;
import com.jlas.shadowing.model.practice.PracticeOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePracticeSessionRequest(
        @NotNull Long videoId,
        @NotNull PracticeMode mode,
        @NotNull PracticeOrder order,
        @Min(1) @Max(500) Integer questionCount,
        boolean allQuestions) {
}
