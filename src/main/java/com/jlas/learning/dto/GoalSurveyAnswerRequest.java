package com.jlas.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record GoalSurveyAnswerRequest(
        @NotBlank String questionCode,
        @NotEmpty List<@NotBlank String> optionCodes
) {}
