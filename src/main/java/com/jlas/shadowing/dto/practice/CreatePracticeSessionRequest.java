package com.jlas.shadowing.dto.practice;

import com.jlas.shadowing.model.practice.PracticeMode;
import com.jlas.shadowing.model.practice.PracticeOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record CreatePracticeSessionRequest(
        @NotNull Long videoId,
        @NotNull PracticeMode mode,
        PracticeOrder order,
        @Min(1) @Max(500) Integer questionCount,
        boolean allQuestions,
        Set<String> levels,
        Set<String> kinds,
        Set<String> selections) {
    public CreatePracticeSessionRequest(Long videoId, PracticeMode mode, PracticeOrder order,
                                        Integer questionCount, boolean allQuestions,
                                        Set<String> levels, Set<String> kinds) {
        this(videoId, mode, order, questionCount, allQuestions, levels, kinds, null);
    }
}
