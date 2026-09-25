package com.jlas.shadowing.dto.practice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitPracticeAnswerRequest(
        @NotNull Long itemId,
        @NotBlank @Size(max = 2000) String answer) {
}
